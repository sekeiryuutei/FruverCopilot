package com.fruver.infrastructure.ai;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;
import com.fruver.application.port.ExtractionUnavailableException;
import com.fruver.application.port.OrderExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Extraccion linguistica con Spring AI (ChatClient + salida estructurada via BeanOutputConverter interno de .entity()).
 * Reintentos y timeouts los gobiernan spring.ai.retry.* y spring.http.client.*: solo errores transitorios, no 4xx.
 * Su salida es UNTRUSTED: la valida ExtractionInterpreter.
 */
@Service
@ConditionalOnExpression("!'${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock')")
public class SpringAiOrderExtractor implements OrderExtractor {

    private final ChatClient chatClient;

    public SpringAiOrderExtractor(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem(SystemPrompts.ORDER_EXTRACTION).build();
    }

    @Override
    public AIOrderResponse extract(ExtractionRequest request) {
        try {
            AIOrderResponse response = chatClient.prompt()
                    .user(u -> u.text("""
                            <turnos_previos>{turnos}</turnos_previos>
                            <pedido_actual>{actual}</pedido_actual>
                            <catalogo_candidato>{catalogo}</catalogo_candidato>
                            <mensaje_cliente>{mensaje}</mensaje_cliente>
                            """)
                            .param("turnos", join(request.recentTurns()))
                            .param("actual", join(request.currentItems()))
                            .param("catalogo", join(request.catalogCandidates()))
                            .param("mensaje", request.message() == null ? "" : request.message()))
                    .call()
                    .entity(AIOrderResponse.class);
            if (response == null) throw new ExtractionUnavailableException("Respuesta vacia del proveedor de IA", null);
            return response;
        } catch (ExtractionUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // timeout, rate limit, proveedor caido o JSON no parseable: error TECNICO, no del pedido
            throw new ExtractionUnavailableException("Fallo la extraccion con IA: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "(ninguno)" : String.join(" | ", values);
    }
}
