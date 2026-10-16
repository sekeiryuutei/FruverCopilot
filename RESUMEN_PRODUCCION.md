# FruverCopilot — Qué se corrigió y cómo desplegar en producción

## 1. Qué estaba mal (en palabras simples)

1. **Claves `MasterKey..` repetidas** en `.env` para base de datos, admin y operador. Si adivinaban una, entraban a todo.
2. **Token de WhatsApp expuesto** en el mismo `.env`. Había que rotarlo en Meta.
3. **Cookies sin `Secure` en producción.** La sesión podía viajar sin cifrar.
4. **Sin freno a intentos de login.** Podían probar claves infinitas.
5. **Cualquiera con login podía ver/editar todo**, sin separar ADMIN y OPERADOR.
6. **La IA podía borrar lo que el empleado acababa de corregir** si terminaba después.
7. **Kilos vs libras se convertía solo** (2 KG → 4 LB) sin avisar. Ahora siempre pide revisión.
8. **El audio aceptaba cualquier tipo de archivo**, riesgo de virus/XSS.
9. **Los tickets con nombre/teléfono quedaban en el log** sin tapar.
10. **Sin copias de seguridad**, base expuesta a Internet, y Docker se armaba sin correr tests.

## 2. Qué se hizo

- **Claves nuevas y distintas** en `.env` (3 generadas el 2026-10-12). WhatsApp desactivado hasta que pegues el token nuevo de Meta.
- **La app no arranca en prod** si dejas `DEV_ENDPOINTS=true`, `SEED_DEMO_DATA=true`, `AI_MODEL=mock`, `COOKIE_SECURE=false` o claves débiles (`ProductionGuard.java`, `BootstrapUsers.java`).
- **Login con bloqueo**: 5 fallos en 10 min = bloqueo 10 min + auditoría `LOGIN_FAILED/LOGIN_OK`. Sesiones: máximo 2, anti-fijación, 30 min. Headers: `CSP`, `nosniff`, `DENY`, `Referrer`.
- **Roles**: `/api/orders` y `/api/sales` exigen `ADMIN` o `OPERADOR`; reportes/clientes/precios solo `ADMIN`. Audios con whitelist + `nosniff`.
- **IA no pisa al humano**: `OrderWriter` revisa `corrected` con `FOR UPDATE`; si ya corregiste, solo deja nota.
- **KG/LB**: cualquier unidad distinta = `UNIT_MISMATCH` → 🟡 a revisión. Sin conversiones silenciosas (`QuoteService.java`).
- **Semáforo honesto**: `GREEN` solo si producto activo + unidad igual; `YELLOW` si hay mismatch; cantidades `0.0004` ya se rechazan en pantalla y servidor.
- **WhatsApp**: firma con tiempo constante, límite 512KB, no crea pedidos vacíos, log de firma inválida.
- **Pantalla**: muestra `details[]` del error 422, valida `0.001` mínimo.
- **Base**: migración `V8__hardening.sql` (CHECK unidad, anti-duplicados, índices `print_job/audit`).
- **Docker**: corre tests en el build, `HEALTHCHECK`, Postgres solo en `127.0.0.1`, scripts `backups/backup.bat` y `backup.sh`.
- **Frontend y backend verificados**: `mvn test` 39 OK (16 integración se omiten sin Docker), `node frontend-tests/operacion.test.js` TODO OK.

## 3. Para desplegar en producción (paso a paso)

### A. Una sola vez en Meta y tu PC
1. En Meta Developers rota **access token** y **app secret** de WhatsApp. Copia los nuevos.
2. En `C:\...\FruverCopilot\.env` pega:
   - `WHATSAPP_ENABLED=true`
   - `WHATSAPP_VERIFY_TOKEN=` un texto largo aleatorio (el mismo que configuras en Meta webhook)
   - `WHATSAPP_APP_SECRET=` nuevo
   - `WHATSAPP_ACCESS_TOKEN=` nuevo
3. Cambia en `.env`:
   - `SPRING_PROFILES_ACTIVE=prod`
   - `COOKIE_SECURE=true`
   - `DEV_ENDPOINTS=false`
   - `SEED_DEMO_DATA=false`, `SEED_DEMO_RESET=false`
   - `AI_MODEL=gpt-4o-mini` (nunca `mock`), `OPENAI_API_KEY=sk-...` real
   - `PRINTER_*` con IP real si hay impresora, o `PRINTER_ENABLED=false`
4. Guarda las 3 claves nuevas (`DB/ADMIN/OPERADOR`) en tu gestor. Son las de `.env` generadas hoy.

### B. Servidor (misma red del local para la impresora)
```bash
cp .env.example .env   # y edita como arriba
docker compose up -d --build
docker compose ps      # espera app y db "healthy"
```
Abre `https://TU-DOMINIO` (HTTPS obligatorio, con proxy inverso Nginx/Caddy). Entra con `admin` + tu clave.

Si cambiaste `DB_PASSWORD` con volumen viejo:
```bash
docker compose down -v   # BORRA datos, solo si es instalación nueva
docker compose up -d --build
```

### C. Verificar
```bash
docker compose logs -f backend
node frontend-tests/operacion.test.js
cd backend
mvn test
OPENAI_API_KEY=sk-... mvn test -Dtest=ExtractionEvalTest
```
Prueba: crea pedido con `/api/dev` en dev, aprueba e imprime, reimprime, busca ventas.

### D. Respaldos (programa esto)
- Windows: `backups\backup.bat` en Tarea programada diaria.
- Linux: `sh backups/backup.sh` en cron diario.
- Guarda `backups/` fuera del servidor. Prueba restaurar en otro equipo una vez al mes.

### E. Reglas que debes definir con el negocio
- ¿1 KG = 2 LB o siempre se bloquea? Hoy bloquea (recomendado).
- Mayorista sin precio mayorista bloquea: carga el precio o usa precio manual.
- "5 mil de tomate" siempre va a revisión 🟡.
- Un domicilio por cliente por ahora.
- Teléfonos: define retención/borrado Ley 1581 de 2012. La API ya enmascara (`****1234`).

## 4. Archivos que cambiaron (por si revisas)

- `backend/.../QuoteService.java`, `OrderWriter.java`, `OrderEditService.java`, `ConversationService.java`, `SseHub.java`, `ExtractionInterpreter.java`, `MessageIngestService.java`
- `backend/.../SecurityConfig.java` + nuevo `LoginAttemptService.java`, `ProductionGuard.java`, `BootstrapUsers.java`
- `backend/.../OrderController.java`, `SaleController.java`, `SalesQueryService.java`, `CustomerService.java`, `ProductCatalogService.java`, `PriceService.java`, `DevController.java`, `WhatsAppWebhookController.java`, `ApiExceptionHandler.java`, `SimulatedPrinter.java`
- `db/migration/V8__hardening.sql`, `application.yml`, `application-prod.yml`, `application-dev.yml`, `Dockerfile`, `docker-compose.yml`, `static/index.html`, `frontend-tests/operacion.test.js`, `.env`, `.env.example`, `backups/*`

> Si algo falla al arrancar en prod, lee el mensaje de `ProductionGuard`/`BootstrapUsers`: te dice exactamente qué variable arreglar.
