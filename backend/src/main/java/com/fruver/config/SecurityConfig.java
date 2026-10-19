package com.fruver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sesion por cookie (HttpOnly, SameSite=Lax) + CSRF con cookie legible por la SPA.
 * Roles: ADMIN (precios, productos, reportes, clientes) y OPERADOR (pedidos, ventas, impresion).
 * /webhook/** se autentica con verify token y firma HMAC, no con sesion.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginAttemptService attempts,
                                    org.springframework.jdbc.core.simple.JdbcClient jdbcAudit) throws Exception {
        http
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/webhook/**", "/login"))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(new LoginBlockFilter(attempts), org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .headers(h -> h
                    .frameOptions(f -> f.deny())
                    .contentTypeOptions(c -> {})
                    .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; media-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")))
            .sessionManagement(s -> s
                    .sessionFixation(f -> f.migrateSession())
                    .maximumSessions(2)
                    .maxSessionsPreventsLogin(false))
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/login.html", "/favicon.ico", "/actuator/health/**", "/webhook/**").permitAll()
                    .requestMatchers("/actuator/info", "/actuator/metrics").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login.html").loginProcessingUrl("/login")
                    .defaultSuccessUrl("/", true)
                    .failureHandler((req, res, ex) -> {
                        String user = req.getParameter("username");
                        String ip = req.getRemoteAddr();
                        attempts.loginFailed(user, ip);
                        try {
                            jdbcAudit.sql("INSERT INTO audit_log(username, action, detail) VALUES (:u, 'LOGIN_FAILED', :d::jsonb)")
                                    .param("u", user == null ? "?" : user.substring(0, Math.min(user.length(), 60)))
                                    .param("d", "{\"ip\":\"" + ip + "\"}").update();
                        } catch (Exception ignored) {}
                        res.sendRedirect("/login.html?error");
                    })
                    .successHandler((req, res, auth) -> {
                        attempts.loginSucceeded(auth.getName(), req.getRemoteAddr());
                        try {
                            jdbcAudit.sql("INSERT INTO audit_log(username, action, detail) VALUES (:u, 'LOGIN_OK', '{}'::jsonb)")
                                    .param("u", auth.getName()).update();
                        } catch (Exception ignored) {}
                        res.sendRedirect("/");
                    })
                    .permitAll())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login.html"))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), r -> r.getRequestURI().startsWith("/api/")));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(JdbcClient jdbc) {
        return username -> jdbc.sql("SELECT username, password_hash, role, enabled FROM app_user WHERE username = :u")
                .param("u", username)
                .query((rs, n) -> User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash"))
                        .roles(rs.getString("role"))
                        .disabled(!rs.getBoolean("enabled"))
                        .build())
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado"));
    }

    /** Fuerza la escritura de la cookie XSRF-TOKEN para que la SPA pueda enviar X-XSRF-TOKEN. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute("_csrf");
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }

    /** Bloquea POST /login si el usuario+IP supero 5 fallos en 10 min. Mensaje generico (no enumera). */
    static final class LoginBlockFilter extends OncePerRequestFilter {
        private final LoginAttemptService attempts;
        LoginBlockFilter(LoginAttemptService attempts) { this.attempts = attempts; }
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            if ("POST".equalsIgnoreCase(req.getMethod()) && "/login".equals(req.getRequestURI())) {
                String user = req.getParameter("username");
                if (user != null && attempts.isBlocked(user, req.getRemoteAddr())) {
                    res.sendRedirect("/login.html?error");
                    return;
                }
            }
            chain.doFilter(req, res);
        }
    }
}
