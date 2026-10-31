# Auditoría integral FruverCopilot — Resumen ejecutivo

**Fecha de corte:** 2026-10-25 13:01 -05 (America/Bogota)  
**Repositorio:** `C:\Users\usuario\Documents\trabajos\FruverCopilot\FruverCopilot`  
**Rama:** `mayorista`  
**Commit final observado:** `dc5d842` (`origin/mayorista`), 29 commits delante de `main` (`700468c`)  
**Alcance:** código, migraciones, configuración, pruebas, flujo WhatsApp → IA → pedido → venta → impresión, seguridad y costos.  
**Modo:** auditoría de solo lectura. No se modificó código, no se desplegó, no se enviaron mensajes reales de WhatsApp, no se imprimieron pedidos reales y no se accedió a producción.

## Veredicto

**NO APTO PARA PRODUCCIÓN TODAVÍA.**

El sistema tiene una arquitectura razonable y varias protecciones correctas, pero existen tres riesgos P0 que pueden causar pérdida de información, ventas/comandas incorrectas o suplantación del webhook:

1. **Secreto de WhatsApp comprometido en un archivo versionado y vigente.** El valor está en `AUDITORIA.md`, coincide con el `WHATSAPP_APP_SECRET` actual y el archivo está en `origin/main` y `origin/mayorista`. No se reproduce el valor en este informe.
2. **Operaciones destructivas disponibles en producción.** Un endpoint ADMIN puede truncar ventas, pedidos, mensajes y auditoría; además, reabrir/anexar pedidos o recibir una adición puede borrar la venta y sus trabajos de impresión, incluso si la comanda ya se está imprimiendo.
3. **Autoaprobación insegura.** El flujo automático crea venta y comanda aunque el pedido tenga `requires_verification=true`, esté vacío o tenga cantidades desconocidas; además, sustituye cantidades faltantes por 1, no exige cliente mayorista y no exige impresora física habilitada. El valor por defecto de `AUTO_PRINT_WHOLESALE` es `true`.

Además, la suite backend **no pasa**: 94 pruebas, 12 errores, 0 fallos de aserción, 0 omitidas, `BUILD FAILURE`. La causa principal es una precedencia de configuración del debounce, no un fallo funcional aislado. No hay evidencia de extremo a extremo con WhatsApp real ni con impresora física.

## Evidencia de pruebas

| Prueba | Resultado | Lectura |
|---|---:|---|
| `node frontend-tests/operacion.test.js` | **92 comprobaciones OK; TODO OK** | La lógica de la PWA probada contra DOM simulado pasa. No prueba backend, red ni hardware. |
| `mvn -B test` en `backend/` | **94 tests, 12 errores, 0 fallos, 0 omitidas; BUILD FAILURE** | 82 pruebas terminaron bien; 12 agotaron 10 s por el debounce efectivo de 20 s. |
| `ExtractionEvalTest` | **3 pruebas ejecutadas con Gemini real** | Efecto secundario: el test carga `.env` y se autohabilita con claves reales. Se realizaron 3 llamadas externas. No se repitió. |
| WhatsApp real | **No ejecutado** | Requiere número, credenciales y autorización; además no se deben enviar mensajes reales en esta auditoría. |
| Impresora física | **No ejecutado** | Requiere equipo ESC/POS en red. `PRINTER_ENABLED=false` en el entorno local. |
| Restauración de backup | **No ejecutado** | Solo hay scripts; no hay evidencia de respaldo programado ni de restore probado. |

Los 12 errores se concentran en `MultimodalBatchBurstIntegrationTest` (1), `SaleFlowIntegrationTest` (8) y `WholesaleFlowIntegrationTest` (3). Todos son `ConditionTimeoutException` de Awaitility a los 10 s. `MultimodalBatchBurstIntegrationTest` pide `app.conversation.debounce-seconds=0`, pero `OrderBatchingCoordinator` prioriza `printer_setting.customer_debounce_seconds`, creado por V17 con valor 20. `SaleFlowIntegrationTest` y `WholesaleFlowIntegrationTest` ni siquiera configuran el debounce: usan el default de 20 s. En los tres casos la espera es de 10 s, así que el timeout está predeterminado por la configuración. Esto también significa que la variable de entorno `CUSTOMER_DEBOUNCE_SECONDS` queda subordinada al valor persistido en base de datos.

## Hallazgos P0

| ID | Hallazgo | Evidencia principal | Impacto |
|---|---|---|---|
| P0-01 | Secreto de WhatsApp en documentación versionada y en historial; coincide con el secreto actual | `AUDITORIA.md` (archivo versionado), commit `d895c2e`; comparación programática contra `.env` | Con el app secret se pueden forjar webhooks válidos, inyectar pedidos y, con la autoaprobación activa, generar ventas/impresiones no autorizadas. Rotar y purgar historial es obligatorio. |
| P0-02 | Borrado destructivo de datos comerciales en rutas normales y endpoint de “reset” en producción | `SaleController.java:323-331`; `OrderIntakeService.java:63-71`; `OrderEditService.java:300-306, 365-370` | Pérdida irreversible de ventas, pedidos, mensajes y auditoría. La reapertura no valida estado, despacho ni impresión en curso. |
| P0-03 | Autoaprobación crea venta/print_job aunque el pedido sea dudoso, vacío o incompleto; cantidades faltantes pasan a 1 | `OrderProcessingService.java:272-288, 302-343`; `SaleService.java:151-217`; `OrderWriter.java:175-177`; `application.yml:82` | Comandas y ventas incorrectas sin intervención humana, posibles totales nulos, productos inventados y “ventas” sin papel si la impresora está simulada. También se puede invocar desde `reprintByOrderId` sin validar estado. |

## Hallazgos P1 principales

| ID | Hallazgo | Impacto |
|---|---|---|
| P1-01 | El webhook responde 200 aunque falle la persistencia o el procesamiento dentro del `try` | Meta no reintenta; un fallo transitorio de base de datos puede perder el pedido sin señal visible. |
| P1-02 | No se procesan `statuses`, no se valida `metadata.phone_number_id` y los tipos no soportados se convierten en texto | No hay confirmación real de entrega, se aceptan eventos de otro número y una reacción/sticker puede crear un pedido vacío. |
| P1-03 | `PRINTED` significa “socket TCP escribió y flush”, no “papel impreso” | No puede prometerse impresión exactamente una vez. Un corte de red, falta de papel o pérdida de la conexión después del envío deja estados falsos. |
| P1-04 | Carreras entre impresión y borrado/reapertura/anexo/reapertura por adición | Se puede imprimir una comanda y luego borrar su venta, o crear una nueva venta mientras el worker tiene un `print_job` en `PRINTING`. |
| P1-05 | WhatsApp solo envía texto/imagen libre; no hay plantillas, ventana de 24 h, opt-out ni estados de entrega | Fuera de la ventana de 24 h el envío falla. No hay evidencia de consentimiento ni mecanismo de baja. `DeliveryStatus.SENT` solo significa HTTP 2xx. |
| P1-06 | Dependencias con CVEs: Boot 3.4.5, Spring 6.2.6, Security 6.4.5, Tomcat 10.1.40, PG JDBC 42.7.5, Spring AI 1.0.0 | Exposición conocida. Boot 3.4.5 tiene un CVE HIGH sin corrección en 3.4.x; se requiere migración mayor evaluada. |
| P1-07 | `BootstrapUsers` reescribe la contraseña en cada reinicio; la API permite claves de 6 caracteres | Un reinicio revierte cambios de contraseña al valor de `.env`; políticas de clave débiles para usuarios creados por API. |
| P1-08 | Productos no encontrados se autoinscriben en el catálogo con unidad inferida | Una alucinación o error de IA contamina el catálogo y puede producir una venta con producto/unidad incorrectos. |
| P1-09 | `ExtractionEvalTest` carga `.env` y ejecuta IA real dentro de `mvn test` | Costo externo no controlado y uso accidental de credenciales reales en pruebas/CI. Ya ocurrió: 3 llamadas a Gemini. |
| P1-10 | No hay backup automático, fuera de sitio ni restore probado; retención de PII/audio no definida | Riesgo operativo y de cumplimiento (Ley 1581 de 2012). |

## Lo que está bien y debe conservarse

- Webhook con HMAC-SHA256, comparación en tiempo constante y rechazo por defecto si falta el secreto (`WhatsAppWebhookController.java:64-67, 112-126`). El problema es el secreto filtrado, no el mecanismo.
- Idempotencia de mensajes por `external_message_id`, captura de `DuplicateKeyException` y venta única por pedido (`sale.order_id UNIQUE`).
- Separación de estados de pedido, conversación e impresión; uso de `FOR UPDATE` y versión optimista en la aprobación manual.
- Precio calculado en backend, `BigDecimal`, snapshot de venta y validaciones de cantidad/precio en tres capas.
- Cola de impresión con `FOR UPDATE SKIP LOCKED`, reintentos y `print_job` como outbox.
- Frontend sin `innerHTML` para datos del pedido y validación de cantidades/precios antes de llamar a la API.
- Documentación base y scripts de respaldo existen, aunque no estén operacionalizados.

## Estado por dimensión

| Dimensión | Estado | Comentario |
|---|---|---|
| Seguridad de credenciales | **Rojo** | Secreto vigente en repositorio; rotación y purga pendientes. |
| Integridad de datos | **Rojo** | Borrado de ventas y autoaprobación sin control suficiente. |
| Flujo funcional | **Amarillo/Rojo** | Camino feliz implementado, pero no validado end-to-end y con decisiones automáticas peligrosas. |
| Impresión | **Amarillo/Rojo** | Encolado y reintento razonables; semántica de “impreso” no confiable y carreras destructivas. |
| WhatsApp | **Amarillo** | Recepción y envío básico; faltan estados, plantillas, ventana, opt-out y control de costos. |
| Pruebas | **Rojo** | Suite backend falla; alta cobertura nominal pero caminos críticos sin pruebas correctas. |
| Operación | **Rojo** | Sin backup probado, sin staging documentado, configuración local en `dev`. |
| Dependencias | **Rojo** | CVEs conocidos; plan de actualización mayor necesario. |

## Configuración observada (sin revelar secretos)

El `.env` local tiene `SPRING_PROFILES_ACTIVE=dev`, `WHATSAPP_ENABLED=true`, `DEV_ENDPOINTS=true`, `AI_AUTO_REPLY=true`, `AUTO_CONFIRM_CLEAR_ORDERS=true`, `PRINTER_ENABLED=false` y `COOKIE_SECURE=false`. `AUTO_PRINT_WHOLESALE` no está definido, por lo que aplica el default `true` de `application.yml:82`. Esto no es una configuración de producción y, además, hace que el flujo destructivo/automático quede activo en el entorno local con impresora simulada.

## Limitaciones de esta auditoría

- No se ejecutó una prueba real contra Meta Cloud API ni contra un número de WhatsApp. No se validó entrega, calidad, plantillas ni ventana de 24 h.
- No se ejecutó una impresión física, corte, apertura de cajón, falta de papel ni recuperación tras corte eléctrico.
- No se probó una restauración real de PostgreSQL ni el volumen de audio.
- No se hizo prueba de carga, caos, concurrencia multi-instancia ni recuperación ante desastres.
- La revisión de dependencias es una consulta OSV puntual, no un análisis continuo ni un pentest.
- La ejecución de `ExtractionEvalTest` realizó 3 llamadas reales a Gemini por diseño del test; no se repitió para evitar más costo. No se enviaron mensajes ni se imprimió nada.
- Las referencias de línea corresponden al commit `dc5d842`. La evidencia inicial se tomó en `f682669`; el cambio posterior a `dc5d842` solo afecta una línea en blanco de V18, según la revisión del árbol. No se hicieron commits, amend, push, reset ni checkout durante esta auditoría.

## Recomendación y autorización

**No desplegar a producción ni usar con clientes reales en este estado.** El orden recomendado es:

1. **Contención inmediata:** rotar `WHATSAPP_APP_SECRET` y el token de acceso, retirar/deshabilitar el reset y la autoaprobación, y congelar cualquier uso con clientes.
2. **Corregir P0:** secretos, borrado de ventas, autoaprobación y cantidades; agregar pruebas de regresión que fallen con el comportamiento actual.
3. **Cerrar P1:** webhook duradero, impresión con semántica honesta, WhatsApp (estados/plantillas/opt-out), backups, bootstrap de usuarios y actualización de dependencias.
4. **Piloto controlado:** número de prueba de WhatsApp, impresora real, staging con backup/restore, carga y aceptación del negocio.

No se aplicó ninguna corrección. Para pasar de auditoría a implementación necesito autorización explícita y el alcance aprobado (por ejemplo, “solo P0”, “P0 + P1”, o una lista de IDs). También necesito confirmación para rotar credenciales y, si se decide purgar el historial git, coordinar la reescritura y el force-push con todos los clones; esa operación no se hará sin autorización específica.

## Documentos de esta auditoría

- [AUDITORIA_SEGURIDAD.md](AUDITORIA_SEGURIDAD.md)
- [AUDITORIA_FLUJO_WHATSAPP_IMPRESION.md](AUDITORIA_FLUJO_WHATSAPP_IMPRESION.md)
- [AUDITORIA_PRUEBAS.md](AUDITORIA_PRUEBAS.md)
- [PLAN_CORRECCIONES.md](PLAN_CORRECCIONES.md)
- [OPTIMIZACION_COSTOS_WHATSAPP.md](OPTIMIZACION_COSTOS_WHATSAPP.md)
- [MATRIZ_CASOS_PRUEBA.md](MATRIZ_CASOS_PRUEBA.md)
