# 🥑 FruverCopilot

Automatización de pedidos de WhatsApp (texto y audio) para un Fruver.

> **La IA ahorra digitación, pero nunca reemplaza el control humano.**
> La IA propone → el backend valida → el humano corrige y aprueba → el backend calcula → el sistema guarda → la impresora imprime.

## Requisitos
- Docker y Docker Compose v2 (no necesitas Java ni Maven instalados: se compila dentro de Docker).
- Una API key de OpenAI (o proveedor compatible) para la IA y la transcripción de audios.
- Opcional: impresora térmica ESC/POS con red (puerto 9100) y una cuenta de WhatsApp Cloud API.

## Cómo levantar
```bash
cp .env.example .env        # edita DB_PASSWORD, ADMIN_PASSWORD, OPENAI_API_KEY
docker compose up -d --build
docker compose ps           # espera a que app y db estén "healthy"
```
Abre **http://localhost:8085** (o tu `APP_PORT`) e ingresa con `ADMIN_USERNAME` / `ADMIN_PASSWORD`.

Probar sin WhatsApp y sin OpenAI (`DEV_ENDPOINTS=true`, `AI_MODEL=mock`), desde PowerShell:
```powershell
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:TU_ADMIN_PASSWORD"))
$body = '{"phone":"573001112233","name":"Juan","text":"3 libras de papa criolla"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8085/api/dev/whatsapp -Headers @{Authorization="Basic $auth"} -ContentType "application/json" -Body $body
```
(`/api/dev/whatsapp` y `/api/dev/messages` son lo mismo. `/api/dev/**` no usa CSRF pero **exige usuario y clave**.)
El pedido aparece solo en la pantalla. Con `PRINTER_ENABLED=false` el ticket se imprime en el log: `docker compose logs -f backend`.

**Modo mock de IA (`AI_MODEL=mock`)**: no llama a OpenAI ni a Internet. Respuestas fijas segun el texto:
| Mensaje contiene | Resultado |
|---|---|
| (cualquier cosa) | Papa criolla 2 LB + Tomate chonto 3 LB → 🟢 |
| `simular ambiguo` | "5 de papa" ambiguo + pregunta al cliente → 🟡 |
| `simular error` | error técnico → 🔴 "IA NO DISPONIBLE" |
| respuesta a un pedido en espera | Papa criolla 5 LB → 🟢 |
Los audios también se "transcriben" offline con una frase fija. **No interpreta el pedido real**; la app se niega a arrancar con mock si `SPRING_PROFILES_ACTIVE=prod`.

Contenedores: `fruvercopilot-postgres` y `fruvercopilot-backend`. Base de datos desde el host: `docker exec -it fruvercopilot-postgres psql -U fruver -d fruver` (o puerto `POSTGRES_PORT`).

**Detener:** `docker compose down` (los datos persisten en volúmenes). Borrar todo: `docker compose down -v`.

## Tests
```bash
cd backend
mvn test        # unitarios siempre; los de integración usan Testcontainers y se omiten si no hay Docker
OPENAI_API_KEY=sk-... mvn test -Dtest=ExtractionEvalTest   # evaluación con el LLM real (no determinista)
```
Sin Maven local: `docker run --rm -v "$PWD":/app -v /var/run/docker.sock:/var/run/docker.sock -w /app maven:3.9-eclipse-temurin-21 mvn test`

Validaciones de cantidades y precios (en 3 capas): la pantalla rechaza negativos/cero/enormes y bloquea el signo `-`; el servidor responde `ITEM_INVALID` (422); la base los rechaza con `CHECK` (migración V7). Cantidad: 0 < x ≤ 100.000; precio manual: 0 ≤ x ≤ 100.000.000.

Prueba de la pantalla de operación (Node, sin dependencias; ejecuta el `index.html` real contra un DOM falso): `node frontend-tests/operacion.test.js`

## Migraciones
**Flyway** (SQL plano y versionado; Liquibase no aporta nada aquí). Se ejecutan solas al arrancar la app: `backend/src/main/resources/db/migration/V1..V6`. `ddl-auto` no se usa. V6 carga un catálogo de **ejemplo con precios ficticios**: ajústalos desde la pestaña *Precios*.

## Datos de demostración
Con `SEED_DEMO_DATA=true` (solo desarrollo) el backend carga al arrancar, una sola vez:

| Producto | Unidad | Minorista | Mayorista |
|---|---|---|---|
| Papa criolla | LB | $3.000 | $2.610 |
| Aguacate Hass | UN | $4.500 | $3.920 |
| Tomate chonto | LB | $2.500 | $2.180 |
| Cebolla cabezona | LB | $1.800 | $1.570 |
| Plátano maduro | UN | $1.500 | $1.310 |
| Naranja malla | UN (1 = una malla) | $8.000 | $6.960 |

| Pedido | Cliente | Estado | Contenido | Total |
|---|---|---|---|---|
| #10003 | Andrés Mendoza | APPROVED, impreso | 3 LB papa criolla + 2 LB tomate chonto | $14.000 |
| #10004 | Maria Camila Silva | APPROVED, impreso (origen audio) | 1 malla de naranja + 3 LB cebolla cabezona | $13.400 |
| #10005 | Felipe Restrepo | REVIEW 🟢 | 2 aguacates + 4 LB papa criolla | $21.000 |

- Los precios de esos productos se **sobrescriben** (queda historial). Los clientes usan teléfonos ficticios (`5700000001xx`): no respondas por WhatsApp a estos pedidos.
- Papa y tomate se venden **por libra** y el sistema bloquea otras unidades (`UNIT_MISMATCH`), por eso "3 unidades de papa" quedó como 3 LB. "Malla" no es una unidad del sistema: se modeló como producto vendido por unidad.
- Es idempotente: si ya existen no vuelve a cargar. Para **repetir la demo** (por ejemplo después de aprobar el #10005): `POST /api/dev/demo/reset` (con `DEV_ENDPOINTS=true`) o arranca con `SEED_DEMO_RESET=true`. Solo toca los pedidos 10003-10005 de esos clientes ficticios; si esos números pertenecen a otros datos, no toca nada.
- Se implementó como componente Java (`DemoDataSeeder`) y no como migración de Flyway: una migración correría también en producción y no se puede apagar.

## Nombres de tablas (convención)
Todo está en **inglés, `snake_case` y singular**, con una excepción: `orders` va en plural porque `order` es palabra reservada de SQL. No hay entidades JPA: el acceso es con `JdbcClient` y SQL plano, así que el esquema real es el de `db/migration/V1..V6`.

| Concepto (español) | Tabla | Notas |
|---|---|---|
| clientes | `customer` | `phone` único, `customer_type` = `RETAIL`/`WHOLESALE` |
| productos | `product` | `unit` = `LB`/`KG`/`UNIDAD`, `active` (no se borran) |
| precios | `product_price` | una fila por producto + `price_type` |
| historial de precios | `product_price_history` | |
| pedidos | `orders` | **plural** |
| líneas de pedido | `order_item` | guarda lo que propuso la IA (`ai_*`) y lo corregido |
| mensajes | `conversation_message` | `external_message_id` único (idempotencia) |
| audios / transcripciones | `audio`, `transcription` | |
| ventas / líneas de venta | `sale`, `sale_item` | snapshot de precios |
| cola de impresión | `print_job` | |
| auditoría / usuarios | `audit_log`, `app_user` | |

Ejemplo (producto con precios; usa `\dt` en `psql` para ver todo):
```sql
INSERT INTO product (name, unit) VALUES ('Mango Tommy', 'KG');
INSERT INTO product_price (product_id, price_type, amount)
SELECT id, t.pt, t.amount FROM product, (VALUES ('RETAIL', 6000), ('WHOLESALE', 5200)) AS t(pt, amount)
WHERE name = 'Mango Tommy';
```
Evita insertar pedidos o ventas directamente en la base: se saltan las validaciones, la auditoría y el cálculo de precios. Para pruebas usa el simulador.

## Arquitectura
```
WhatsApp ─► Webhook (firma HMAC) ─► [tx corta] mensaje + pedido (idempotente por externalMessageId)
                                      │  (audio: descarga → almacenamiento → Speech-to-Text)
                                      ▼
                     candidatos de catálogo (pg_trgm/unaccent) ─► OrderExtractor (Spring AI, SIN transacción)
                                      │ falla ─► 🔴 "IA NO DISPONIBLE" (el pedido no se pierde)
                                      ▼
              ExtractionInterpreter: valida (UNTRUSTED) + resuelve catálogo + ReviewEvaluator ─► 🟢🟡🔴
                                      ▼
                    [tx corta] guarda propuesta ─► SSE ─► pantalla ─► empleado corrige ─► APROBAR E IMPRIMIR
                                      ▼
        [1 tx] SaleService: valida + precio por tipo de cliente + snapshot + venta + print_job
                                      ▼ (fuera de la tx)
                 PrintService (SKIP LOCKED) ─► ThermalPrinterService ─► ESC/POS TCP :9100
                                      └─ falla ─► venta CONFIRMADA + PRINT_FAILED ─► [REINTENTAR]
```
Paquetes (`backend/src/main/java/com/fruver`): `domain` (enums, reglas puras: `ReviewEvaluator`, `Money`), `application` (puertos, DTOs, casos de uso), `infrastructure` (Spring AI, audio, WhatsApp, impresión, persistencia), `interfaces.rest`. Los puertos (`OrderExtractor`, `SpeechToTextService`, `AudioStorageService`, `MessagingGateway`, `ThermalPrinterService`) permiten cambiar proveedor sin tocar el dominio.

**Modelo de datos:** `product`, `product_price` (una fila por producto+tipo de precio, con `valid_from/valid_to`), `product_price_history`, `customer`, `orders` + `order_item` (guarda `ai_*` original y valor corregido), `conversation_message`, `audio`, `transcription`, `sale` (`UNIQUE(order_id)`) + `sale_item` (snapshot completo), `print_job` (outbox), `audit_log`, `app_user`.

**Decisiones clave**
- El **precio** lo decide `QuoteService`: cliente → tipo → `PriceType` → precio vigente. Nunca el LLM ni el frontend; el backend recalcula siempre (`BigDecimal`, COP, escala 0, `HALF_UP`).
- El **semáforo** es determinístico; la IA solo puede empeorarlo, nunca mejorarlo.
- **Sin transacciones abiertas** durante LLM, STT, WhatsApp o impresora.
- **Concurrencia:** `orders.version` (bloqueo optimista manual) + `SELECT … FOR UPDATE` al aprobar; `sale.order_id UNIQUE` como red de seguridad. Aprobar dos veces devuelve la misma venta.
- **Tres estados separados:** pedido (`RECEIVED…APPROVED`), conversación (`WAITING_FOR_CUSTOMER…`) e impresión (`PRINT_*`).
- **Un mensaje de confirmación al cliente no es una venta.** Solo `approve` crea la venta.
- Tecnologías **no** incluidas a propósito: Redis (sin caché necesaria), RabbitMQ/Kafka (eventos internos + outbox en PostgreSQL bastan), MinIO (interfaz lista; disco con volumen Docker por ahora).

## Configuración
Todas las variables están documentadas en `.env.example`.

**IA:** `OPENAI_API_KEY`, `AI_MODEL`, `OPENAI_BASE_URL`. Reintentos con backoff exponencial solo para errores transitorios (`spring.ai.retry.*`, no reintenta 4xx); timeouts en `spring.http.client.*`. Políticas: `AI_AUTO_REPLY` y `AUTO_CONFIRM_CLEAR_ORDERS` (ambas `false` por defecto: el empleado revisa antes de escribirle al cliente).

**Impresora:** `PRINTER_ENABLED=true`, `PRINTER_HOST`, `PRINTER_PORT` (9100), `PRINTER_COLUMNS` (32 = 58 mm, 42/48 = 80 mm). El texto se envía en ASCII sin tildes para no depender de la tabla de caracteres. **El backend debe poder alcanzar la IP de la impresora**: despliega el stack en un equipo de la misma red del local. Si lo pones en la nube necesitarás un agente local que consulte `print_job` (la tabla ya está pensada para eso).

**WhatsApp Cloud API:** `WHATSAPP_ENABLED=true` y las variables `WHATSAPP_*`. En Meta configura el webhook `https://TU-DOMINIO/webhook/whatsapp` con tu `WHATSAPP_VERIFY_TOKEN` y suscribe el campo `messages`. Requiere HTTPS público. Sin `WHATSAPP_APP_SECRET` el webhook rechaza todo (fail-closed). Fuera de la ventana de 24 h Meta solo permite plantillas aprobadas: las preguntas al cliente fallarán y quedarán marcadas *FALLÓ EL ENVÍO*.

**Roles:** `ADMIN` (precios, productos, clientes, reportes) y `OPERADOR` (pedidos, aprobar, imprimir, reimprimir).

## API principal (`/api`, sesión por cookie + `X-XSRF-TOKEN`)
| Método | Ruta | Rol |
|---|---|---|
| GET | `/orders`, `/orders/{id}` | todos |
| PUT | `/orders/{id}` (edición con `version`) | todos |
| POST | `/orders/{id}/approve` · `/cancel` · `/reply` | todos |
| GET | `/orders/{id}/messages` · `/audio` | todos |
| GET | `/sales` (from,to,customerType,productId,q) · `/sales/{id}/items` | todos |
| POST | `/sales/{id}/reprint` | todos |
| GET | `/products` · POST/PATCH `/products` · PUT `/products/{id}/prices/{RETAIL\|WHOLESALE}` · GET `/products/{id}/price-history` | lectura todos; resto ADMIN |
| GET | `/reports/sales` · `/customers` · PUT `/customers/{id}/type` | ADMIN |
| GET | `/stream` (SSE) · `/me` · `/actuator/health/{liveness,readiness}` | — |

Errores: `{timestamp, status, code, message, details[]}` (`ORDER_REQUIRES_REVIEW` 422, `ORDER_CONFLICT` 409, …).

## Estado de verificación (léelo)
- ✅ **Verificado ejecutando código:** lógica de dominio (semáforo, dinero, validación de la salida del LLM, formato de ticket, bytes ESC/POS) y la sintaxis del JavaScript del frontend.
- ⚠️ **No verificado por mí:** la compilación completa con Maven/Spring, las migraciones contra PostgreSQL y las pruebas de integración. Fueron escritas sin acceso a Maven, red ni Docker. Ejecuta `docker compose up --build` y `mvn test` y corrige lo que aparezca; lo más probable son ajustes menores de API entre versiones.
- Nunca se probó con un LLM real, con WhatsApp real ni con una impresora física.

## Antes de llevarlo a producción
1. `SPRING_PROFILES_ACTIVE=prod`, `DEV_ENDPOINTS=false`, `COOKIE_SECURE=true` detrás de HTTPS (proxy inverso) y contraseñas reales.
2. Validar el prompt con audios y mensajes reales del negocio y revisar el `ExtractionEvalTest`; medir el % de pedidos corregidos en *Reportes*.
3. **Reglas de negocio pendientes de definir:** conversión KG↔LB (hoy una unidad distinta a la del producto bloquea la aprobación), qué pasa si un cliente mayorista no tiene precio mayorista (hoy bloquea hasta fijar precio manual o cargarlo), pedidos por valor en dinero ("5 mil de tomate": hoy siempre a revisión).
4. Un solo domicilio por cliente (no hay tabla `customer_address` aún) y un solo tipo de producto por unidad.
5. Respaldos de PostgreSQL (`pg_dump` programado) y del volumen `audio_data`.
6. Sin límite de intentos de login ni auditoría de lectura; añade rate limiting en el proxy si se expone a Internet.
7. Los teléfonos completos se guardan en base de datos (la API los enmascara); define retención según habeas data (Ley 1581 de 2012).
