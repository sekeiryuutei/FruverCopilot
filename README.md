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
Abre **http://localhost:8080** (o tu `APP_PORT`) e ingresa con `ADMIN_USERNAME` / `ADMIN_PASSWORD`.

Probar sin WhatsApp (con `DEV_ENDPOINTS=true`):
```bash
# Inicia sesión primero en el navegador; para probar por curl usa el simulador desde la consola del navegador:
fetch('/api/dev/messages',{method:'POST',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1])},
  body:JSON.stringify({phone:'573001112233',name:'Juan',text:'vecino me manda 3 libras de papa criolla y 4 aguacates'})})
```
El pedido aparece solo en la pantalla (SSE). Con `PRINTER_ENABLED=false` el ticket se imprime en el log: `docker compose logs -f app`.

**Detener:** `docker compose down` (los datos persisten en volúmenes). Borrar todo: `docker compose down -v`.

## Tests
```bash
cd backend
mvn test        # unitarios siempre; los de integración usan Testcontainers y se omiten si no hay Docker
OPENAI_API_KEY=sk-... mvn test -Dtest=ExtractionEvalTest   # evaluación con el LLM real (no determinista)
```
Sin Maven local: `docker run --rm -v "$PWD":/app -v /var/run/docker.sock:/var/run/docker.sock -w /app maven:3.9-eclipse-temurin-21 mvn test`

## Migraciones
**Flyway** (SQL plano y versionado; Liquibase no aporta nada aquí). Se ejecutan solas al arrancar la app: `backend/src/main/resources/db/migration/V1..V6`. `ddl-auto` no se usa. V6 carga un catálogo de **ejemplo con precios ficticios**: ajústalos desde la pestaña *Precios*.

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
