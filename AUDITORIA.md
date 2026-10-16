# Auditoría FruverCopilot — Informe para corrección (CORREGIDO 2026-10-12)

> Estado: TODO CORREGIDO y verificado con `mvn -B compile` BUILD SUCCESS,
> `mvn -B test` 39 OK / 16 omitidos sin Docker, `node frontend-tests/operacion.test.js` TODO OK.
> Detalle simple para desplegar: ver `RESUMEN_PRODUCCION.md`.

Fecha: 2026-10-11
Alcance: completa (seguridad, correctitud, datos, config/despliegue, frontend)
Repo: `C:\Users\usuario\Documents\trabajos\FruverCopilot\FruverCopilot`
Base: README + `backend/src`, `db/migration V1..V7`, `application*.yml`, `docker-compose.yml`, `Dockerfile`, `static/index.html`, `login.html`, `frontend-tests/operacion.test.js`, `.env` / `.env.example`

> Cómo usar este doc: corrige por severidad. Cada hallazgo trae `archivo:línea`, evidencia, impacto y fix concreto.

---

## 0. Resumen ejecutivo

| Severidad | N | Temas |
|---|---|---|
| Crítica | 2 | Secretos reales en `.env` + reuso `MasterKey..`; token WhatsApp en claro |
| Alta | 7 | `COOKIE_SECURE=false` sin guard en prod; sin rate-limit login; `OrderController` sin roles; carrera IA vs humano `OrderWriter`; contradicción KG↔LB código vs test; dev endpoints + seed activos; sin verificación Maven/PG/LLM real |
| Media | ~12 | Headers seguridad, SSE bloqueante, `ConversationService` no atómico, LIKE sin escape, audio `mime` atacante, tickets con PII en log, sin backups, DB expuesta, oráculo búsqueda teléfono, truncado 50 ítems, etc. |
| Baja | ~8 | `verify` no constant-time, CSP, tags Docker flotantes, `DEBUG`, UNIQUE/CHECK faltantes, etc. |

Lo que está bien: transacciones cortas sin I/O externo, `SaleService.approve` en 1 tx + `FOR UPDATE` + `UNIQUE(order_id)`, HMAC webhook constant-time + fail-closed, `JdbcClient` con named params (no SQLi), frontend sin `innerHTML` (usa `createTextNode`), 3 capas de validación cantidad/precio, `BigDecimal` escala 0 `HALF_UP` + snapshot venta.

---

## 1. CRÍTICA — corregir ya

### C1. Credenciales débiles y reutilizadas en `.env`
- ` .env:21,25,27`
```ini
DB_PASSWORD=MasterKey..
ADMIN_PASSWORD=MasterKey..
OPERATOR_PASSWORD=MasterKey..
```
- Impacto: comprometer una compromete DB + admin + operador. Diccionario trivial.
- Fix:
  1. Generar 3 passwords distintos >=20 chars (`openssl rand -base64 24`).
  2. Cambiar en `.env` local, recrear usuarios/DB (hash BCrypt en `app_user`).
  3. No copiar este `.env` a prod. Usar vault/secret manager.

### C2. Secreto + token WhatsApp en claro en disco
- `.env:44,46,47`
```ini
WHATSAPP_APP_SECRET=b0f945f2ab220ae51f585af77bfa86c5
WHATSAPP_ACCESS_TOKEN=EAAPqB76O7IABStDWtWk...
WHATSAPP_PHONE_NUMBER_ID=1381562511699163
```
- Impacto: control de número WhatsApp, suplantación, costo Meta. `.gitignore:1` ignora `.env` (bien, verificado que solo `.env.example` está en git), pero queda en disco/backups/layers Docker (`docker-compose.yml:32` `env_file: .env`).
- Fix:
  1. Revocar/rotar token y `app-secret` en Meta ya.
  2. Mover a vault, inyectar solo en runtime, nunca en imagen.
  3. Verificar `git log --all -- .env` vacío y rotar si alguna vez se subió. Purgar backups con el token viejo.

---

## 2. ALTA

### A1. Cookie `Secure=false` por defecto y sin enforcement en prod
- `backend/src/main/resources/application.yml:4-9`:
```yaml
cookie: { http-only: true, same-site: lax, secure: ${COOKIE_SECURE:false} }
```
- `.env:11` `COOKIE_SECURE=false`
- `backend/src/main/resources/application-prod.yml:1-11` no exige `secure:true`. `config/ProductionGuard.java:14-23` bloquea `DEV_ENDPOINTS/SEED/mock` en prod pero no `COOKIE_SECURE`.
- Impacto: `JSESSIONID` + Basic `/api/dev` en claro si hay HTTP.
- Fix: en `ProductionGuard` lanzar si `!cookie.secure` con perfil `prod`; o fijar `secure: true` en `application-prod.yml`. Servir solo detrás de HTTPS (proxy) como dice `README.md:169`.

### A2. Sin rate-limit / lockout en `/login`
- `config/SecurityConfig.java:41-54` — no hay `sessionManagement`, `maximumSessions`, `sessionFixation`, ni `RateLimiter/Bucket/lockout` (grep 0). `pom.xml` sin Bucket4j/Redis.
- `.env` con password débil = fuerza bruta trivial.
- Fix: `AuthenticationFailureHandler` con backoff + bloqueo temporal + `audit_log` login éxito/fracaso + alerta. Si se expone a Internet, rate-limit en proxy (el README p.6 ya lo pide). Añadir `sessionFixation().migrateSession()` + `maximumSessions(2)` + timeout.

### A3. `OrderController` sin `@PreAuthorize` — OPERADOR puede todo en pedidos/ventas
- `interfaces/rest/OrderController.java:53-108` — `board/get/update/approve/cancel/reply/messages/audio` sin rol. `SaleController.java:54-59` `reprint` sin rol.
- Positivo: `ProductController.java:42,56,62,68` y `SaleController.java:62,70,76` sí exigen `ADMIN` para precios/reportes/clientes. `index.html:260` solo oculta tabs en cliente (bypasseable).
- Impacto: sin separación real OPERADOR↔ADMIN en flujo comercial + IDOR: `GET /api/orders/{id}` y `/audio` con IDs secuenciales exponen PII (`customerName/address/notes/messageText`) e historial ajeno.
- Fix: añadir `@PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")` explícito en pedidos/ventas + decidir si `reprint/approve/cancel` es solo OPERADOR+ADMIN o ADMIN; restringir `/sales?q` teléfono a ADMIN o exigir mín. 4 dígitos + audit. Añadir test auth 403.

### A4. Carrera IA vs humano: `OrderWriter.applyExtraction` pisa edición sin `version`
- `application/service/OrderWriter.java:30-38`:
```java
UPDATE orders SET ... version=version+1 WHERE id=:id AND status IN (...)
DELETE FROM order_item WHERE order_id=:o // + re-INSERT
```
- vs `OrderEditService.java:39,44` que sí hace `findForUpdate + if(version!=edit.version) throw ORDER_CONFLICT`.
- Impacto: si IA (`OrderProcessingService.doProcess:98-99` largo fuera de tx) termina después del `PUT` del empleado, borra corrección humana sin `409`.
- Fix: pasar `expectedVersion` al writer o `SELECT ... FOR UPDATE` + comparar `version`; si difiere, no pisar y marcar `ORDER_CONFLICT` / re-evaluar. Añadir test de carrera.

### A5. Contradicción KG↔LB: código convierte, test exige bloqueo
- `application/service/QuoteService.java:78-86,93-94` convierte `KG↔LB` (`1KG=2LB`, `1LB=0.5KG`) y no devuelve `UNIT_MISMATCH`.
- `src/test/.../SaleFlowIntegrationTest.java:130-135` espera `ORDER_REQUIRES_REVIEW + UNIT_MISMATCH` para `2 KG` de papa (producto `LB`).
- Impacto: o el test está roto o se factura `2KG` como `4LB` sin aviso comercial.
- Fix: decidir regla (README p.3 dice “hoy bloquea”): o quitar conversión y siempre `UNIT_MISMATCH`, o mantenerla con aviso explícito + factor visible en ticket. Actualizar test + `TicketFormatter`.

### A6. Dev + seed activos en `.env` de ejemplo
- `.env:9,12,15` `SPRING_PROFILES_ACTIVE=dev`, `DEV_ENDPOINTS=true`, `SEED_DEMO_DATA=true`.
- `DevController.java:42-54` permite inyectar pedidos/audios falsos y `POST /api/dev/demo/reset` borra `10003-10005` (`DemoDataSeeder.java:184-188`). `ProductionGuard` solo bloquea con perfil `prod`; desplegar con `dev` lo evita. `DevController:23` sin `@Size/@Pattern` en `phone/text`.
- Fix: defaults `DEV_ENDPOINTS=false`, `SEED_DEMO_DATA=false`; exigir además red interna/header; validar `phone` E.164 + `text<=1000` igual que `ConversationService.java:38-39`; limitar `file.getBytes()` (solo `max-file-size:20MB` hoy).

### A7. Nunca verificado end-to-end (bloquea prod)
- `README.md:163-166` admite: no compilado con Maven/Spring, migraciones no probadas en PG, integración omitida, nunca probado con LLM/WhatsApp/impresora real.
- `backend/Dockerfile:4` hace `mvn -q -B package -DskipTests` (empaqueta sin tests).
- Fix antes de prod:
```bash
docker compose up -d --build
docker compose ps  # app y db healthy
cd backend && mvn test
OPENAI_API_KEY=sk-... mvn test -Dtest=ExtractionEvalTest
node frontend-tests/operacion.test.js
```
Corregir “ajustes menores de API” que aparezcan.

---

## 3. MEDIA

### M1. Sin headers de defensa
- `SecurityConfig.java` — grep `headers(|frameOptions|hsts|contentSecurity` = 0.
- Fix: `HSTS` (en proxy HTTPS), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `CSP: default-src 'self'; object-src 'none'; base-uri 'self'`.

### M2. Sin `sessionManagement`
- Ver A2. Fix: `migrateSession`, `maximumSessions`, `invalidSessionUrl`, timeout.

### M3. Audio sirve `mime` controlado por atacante → XSS almacenado
- `OrderController.java:101-108`:
```java
String mime = row[1]==null?"audio/ogg":row[1].split(";")[0].trim();
.contentType(MediaType.parseMediaType(mime))
```
`mime` viene de `DevController:51-52 file.getContentType()` o `mime_type` Meta. Sin whitelist, sin `Content-Disposition`, sin `nosniff`.
- Fix: whitelist `audio/*` (mp3/m4a/wav/webm/amr/ogg), `Content-Disposition: inline; filename="..."`, `nosniff`, CSP, ideal dominio aparte.

### M4. `SseHub` bloquea hilos IA/scheduler
- `application/service/SseHub.java:31-43` `for(e:emitters){e.send(...)}` síncrono llamado desde `OrderProcessingService:70` (`@Async ai-` pool 4-8) y `MessageIngestService:65`.
- Fix: `send` con timeout + executor dedicado / cola + `CopyOnWrite`; heartbeat ya existe `:45-48`.

### M5. `ConversationService.send()` no atómica
- `ConversationService.java:37,52-53` sin `@Transactional`: `INSERT conversation_message` + `UPDATE orders conversation_status` en autocommit. Caída intermedia = huérfano. Dos `send` concurrentes indeterminados.
- Fix: `@Transactional` + `SELECT ... FOR UPDATE` del pedido.

### M6. LIKE sin escape ni límite
- `CustomerService.java:24-27`, `ProductCatalogService.java:71-77`, `SalesQueryService.java:31-48` hacen `"%"+term+"%"` sin escapar `%_\\` ni truncar. `q="%%%"` lista todo (`LIMIT 100/500`).
- Fix: escape `ESCAPE '\'` + `q<=100 chars` + whitelist `customerType` enum.

### M7. Ticket con PII en log
- `SimulatedPrinter.java:21` `log.info("[IMPRESORA SIMULADA]\n{}", text)` con `PRINTER_ENABLED=false` (default). Todo nombre/teléfono/dirección/total en `docker compose logs`.
- Fix: enmascarar (`Phones.mask()`) o bajar a `DEBUG` + definir retención Ley 1581 (`README:175` p.7).

### M8. Sin backups
- `docker-compose.yml:49-51` volúmenes `pgdata/audio_data` persisten, pero sin `pg_dump` ni copia audio. `down -v` borra todo (`README:41`).
- Fix: cron `pg_dump` + copia volumen + prueba restore. Es `README:173` p.5.

### M9. DB expuesta al host
- `docker-compose.yml:15-16` `"${POSTGRES_PORT:-5439}:5432"`.
- Fix en prod: no publicar `5439` o `127.0.0.1:5439:5432` + firewall/red interna.

### M10. Oráculo de teléfono aunque API enmascara
- `Phones.java:6-9 mask()` bien usado (`CustomerService:28`, `OrderViewService:72`), pero `SalesQueryService:46 s.customer_phone ILIKE :q` permite confirmar número por hits.
- Fix: ver A3.

### M11. Truncado silencioso >50 ítems
- `ExtractionInterpreter.java:28,44` `MAX_ITEMS=50`, `if(items.size()>=MAX) continue` sin forzar `YELLOW/RED`.
- Fix: marcar revisión + nota “pedido grande”.

### M12. Webhook sin límite tamaño/rate-limit + log pobre
- `WhatsAppWebhookController.java:54 @RequestBody String body` bufferiza todo; siempre `200` (`:67-71`) sin `log.warn` IP/hash.
- Fix: límite tamaño + `429` + log firma inválida sin PII + métrica.

---

## 4. BAJA / INFO

- **B1.** `WhatsAppWebhookController.java:42-51` `expected.equals(token)` no constant-time (el HMAC sí usa `MessageDigest.isEqual`). Usar constant-time también en verify. Si `challenge==null` y token válido → `ok(null)`.
- **B2.** `OrderEditService.java:112` recalcula `GREEN` solo por “producto existe+activo”, sin `UNIT_MISMATCH/NO_PRICE/NO_QUANTITY` (lo corrige `approve→422`, pero confunde en UI).
- **B3.** `qty=0.0004` frontend `ok` (`operacion.test.js:147`) vs backend `setScale(3)→0.000→NO_QUANTITY` (`OrderEditService:68`, `QuoteService:91`, `V7 CHECK`). Alinear validación.
- **B4.** `PriceService.java:31` `amount.setScale` sin null-check (hoy cubierto por `@NotNull`, frágil).
- **B5.** `V1:11 UNIQUE(lower(name))` no unaccent-insensible → `Piña/Pina` duplicables mientras búsqueda usa `unaccent+similarity>0.45`. `order_item.unit VARCHAR(10)` sin CHECK; `sale_item` sin `UNIQUE(sale_id,product_id)`; `ix_print_job_pending` no cubre `sale_id`.
- **B6.** `V7 CHECK ... NOT VALID` solo valida filas nuevas (intencional, verificado en test) — documentar.
- **B7.** `application-dev.yml:1-3` `com.fruver: DEBUG` verboso. `ApiExceptionHandler.java:64` loguea `cause.message` (puede traer SQL). Sanitizar.
- **B8.** `Dockerfile`: tags flotantes (`maven:3.9-eclipse-temurin-21`, `21-jre`), wildcard `*.jar`, sin `HEALTHCHECK` propio. Pinear por digest + correr tests en build.
- **B9.** `pom.xml:5-9` Boot `3.4.5` (abr-2025) vs `3.5.x` — revisar CVE. Sin `dependency-check/enforcer`. Sin Redis/Rabbit/MinIO a propósito (correcto).
- **B10.** `management:49-52` expone `health,info,metrics`; `metrics` autenticado a cualquier rol — restringir a ADMIN.
- **B11.** Detección `contradic` substring (`ExtractionInterpreter:59-60`) evadible con sinónimos — documentar.
- **B12.** `PriceBook` filtra `valid_from/to` pero `V1:23 UNIQUE(product_id,price_type)` impide programar futuros — comentario engañoso.
- **INFO seguro:** `JdbcClient` todo con `:named params`, dinámicos solo fragmentos fijos + tokens de `TextTokens` (`[^a-z]+`, `<=8` stems) — no SQLi. `LocalAudioStorage:20,44-49` anti-traversal (`SAFE_KEY` + `normalize` + whitelist extensión) + `USER fruver:10001` no-root. `login.html` genérico (no enumera), `ApiExceptionHandler` 500 genérico + prod `never stacktrace`.

---

## 5. Checklist “Antes de prod” (de `README:168-175`, con dueño)

- [ ] C1+C2 rotados, passwords únicos, vault.
- [ ] A1 `COOKIE_SECURE=true` + HTTPS + `ProductionGuard` ampliado.
- [ ] A2 rate-limit login + sesiones + auditoría login.
- [ ] A3 roles en pedidos/ventas + test 403 + IDOR.
- [ ] A4 race IA vs humano + test.
- [ ] A5 regla KG↔LB definida + ticket + test.
- [ ] A6 `DEV_ENDPOINTS=false`, `SEED_DEMO_DATA=false`, validación dev.
- [ ] A7 `docker compose up --build` + `mvn test` + `ExtractionEvalTest` real + prueba impresora/WhatsApp reales.
- [ ] M1 headers/CSP/HSTS. M3 audio whitelist. M4 SSE async. M5 tx send. M6 LIKE escape. M7 PII log. M8 backups + restore. M9 no exponer 5439. M12 límite webhook.
- [ ] Pendientes negocio: mayorista sin precio mayorista (hoy bloquea), “5 mil de tomate” siempre a revisión, un domicilio/cliente, retención habeas data Ley 1581.

---

## 6. Orden de corrección sugerido (1-2 días)

1. C1+C2 + A6 (secretos + cerrar dev).
2. A1+A2+M1 (cookie/HTTPS/rate-limit/headers/sesión).
3. A3 (roles + IDOR).
4. A4+M5+M4 (concurrencia + SSE).
5. A5+B2+B3 (reglas negocio + semáforo).
6. M3+M6+M7+M12+B7 (higiene inputs/logs).
7. M8+M9+B8+B9+B10 (despliegue).
8. A7 validación final + tests faltantes: doble `approve` con hilos, doble webhook mismo `external_id`, firma `403/200`, `ConversationService FAILED`, `PrintService SKIP LOCKED` multi-worker, `KG/LB 2x`.

---
*Generado por auditoría estática (lectura código/config). No se ejecutó Maven/Docker ni LLM real — ver A7.*
