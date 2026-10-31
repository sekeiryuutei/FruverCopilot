# Plan de correcciones — FruverCopilot

**Fecha:** 2026-10-25  
**Commit de referencia:** `dc5d842` (rama `mayorista`)  
**Estado:** propuesta. **No se ha aplicado ningún cambio de código, configuración, credencial, historial ni despliegue.**  
**Regla de entrada:** no se modificará nada hasta recibir autorización explícita y el alcance aprobado.

## 1. Objetivo y principio de salida

El objetivo no es “hacer pasar la suite” ni maquillar los síntomas. Es eliminar la posibilidad de:

- perder ventas, pedidos, mensajes o auditoría;
- crear ventas/comandas automáticas a partir de datos dudosos;
- aceptar webhooks forjados o perder mensajes por una caída transitoria;
- afirmar que algo se imprimió o se entregó sin evidencia;
- operar con un secreto comprometido o dependencias vulnerables.

La salida de este plan es un **piloto controlado** con número de prueba, impresora real, staging, backups y aceptación del negocio. Producción masiva queda fuera hasta completar la compuerta final.

## 2. Principios de diseño

1. **Fail closed:** ante duda, error o configuración incompleta, el sistema no vende, no imprime y no envía; deja el pedido para revisión.
2. **Dinero inmutable:** una venta aprobada no se borra. Se corrige con anulación/reemplazo auditado.
3. **La IA propone, el backend decide:** el LLM nunca fija precio, cantidad, unidad ni producto final sin validación.
4. **Idempotencia en cada frontera:** mensaje, webhook, aprobación, impresión, envío y reintento.
5. **Estados honestos:** “aceptado por Meta” ≠ “entregado”; “socket escribió” ≠ “papel impreso”.
6. **Trazabilidad:** toda decisión automática y toda corrección tienen actor, motivo, versión y correlación.
7. **Cambios pequeños y reversibles:** migraciones aditivas, feature flags, staging y rollback.
8. **Medir antes de optimizar:** costo por mensaje/categoría, tokens, impresiones y fallos.

## 3. Fase 0 — Contención inmediata (sin esperar al código)

Estas acciones son operativas y de seguridad. **Requieren autorización** y coordinación con quien administra Meta, el servidor y el repositorio.

| # | Acción | Responsable | Evidencia de cierre |
|---:|---|---|---|
| 0.1 | Rotar `WHATSAPP_APP_SECRET`, `WHATSAPP_ACCESS_TOKEN` y `WHATSAPP_VERIFY_TOKEN` en Meta; actualizar el despliegue y revocar valores viejos. | Seguridad/Operación | Meta muestra el nuevo secreto; el valor viejo ya no valida; webhook de prueba responde. |
| 0.2 | Desactivar el uso con clientes reales y congelar autoaprobación/impresión automática. | Negocio/Operación | `AUTO_PRINT_WHOLESALE=false`, `PRINTER_ENABLED=false` o impresora de prueba; pedidos en revisión. |
| 0.3 | Retirar el endpoint de reset del build de producción o bloquearlo por perfil/entorno. | Backend | `POST /api/sales/reset-test-data` responde 404/403 en prod. |
| 0.4 | Tomar backup verificable antes de tocar datos; preservar logs/evidencia del incidente. | Operación | Backup con hash/fecha y restore de prueba en staging. |
| 0.5 | Revisar actividad reciente en Meta Business, accesos al repositorio y clones; buscar usos del secreto. | Seguridad | Informe de accesos y búsqueda sin hallazgos distintos del ya conocido. |
| 0.6 | Activar escaneo de secretos (Gitleaks/TruffleHog) y de dependencias (OSV/Dependabot) en CI. | DevSecOps | Pipeline falla si detecta un secreto o CVE crítica. |
| 0.7 | No purgar el historial Git todavía: la rotación es lo urgente; la purga se planifica con todos los clones. | Seguridad/DevOps | Plan de purga aprobado y ventana coordinada. |

**No hacer:** cambiar solo el valor de `.env` local y creer que el secreto quedó rotado; el valor sigue en el historial y en clones. Tampoco borrar `AUDITORIA.md` sin rotar: el secreto ya está publicado.

## 4. Fase 1 — P0 (bloqueantes de producción)

### 4.1 P0-01 — Secreto de WhatsApp comprometido

**Cambios**

1. Rotar los tres secretos y actualizar el gestor de secretos del despliegue; no usar `.env` versionado en producción.
2. Eliminar el valor de `AUDITORIA.md` y de cualquier otro documento; dejar una referencia a variable de entorno.
3. Purgar el secreto de **todas** las ramas y tags con `git filter-repo`/BFG, previa coordinación de clones, PRs abiertos, imágenes y backups. Esto es destructivo y requiere autorización específica; no se hará en la misma ventana que la rotación sin plan de comunicación.
4. Añadir escaneo de secretos en pre-commit y CI; prohibir secretos en documentación.
5. Revisar logs, imágenes Docker, artefactos y backups por si contienen el valor viejo.

**Criterios de aceptación**

- El valor viejo ya no autentica un webhook; Meta confirma el nuevo.
- `git grep` y el escáner no encuentran el secreto en el árbol actual.
- El historial publicado ya no lo contiene (o existe una aceptación formal y documentada si la purga se difiere).
- Un webhook firmado con el secreto viejo devuelve 403 y uno firmado con el nuevo es aceptado.
- No hay secretos en logs ni en la imagen.

**Riesgo de no hacerlo:** suplantación de webhooks, inyección de pedidos y, con autoaprobación, ventas/impresiones no autorizadas.

### 4.2 P0-02 — Borrado destructivo e inmutabilidad de ventas

**Cambios**

1. **Reset:** eliminar `SaleController.resetTestData` del build de producción. Si se necesita para demo, moverlo a una aplicación/perfil `demo` con base separada, confirmación explícita y auditoría. Nunca `TRUNCATE` en prod.
2. **Ventas inmutables:** quitar los `DELETE FROM sale`, `sale_item` y `print_job` de `OrderIntakeService.record`, `OrderEditService.mergeIntoOrder` y `OrderEditService.reopen`. Una venta aprobada no se borra.
3. **Corrección:** introducir un flujo de anulación/reemplazo (`VOIDED`/`SUPERSEDED`) con motivo, actor, fecha, venta anterior y nueva. Alternativas a validar con negocio:
   - tabla `sale_revision`/`sale_void` y una venta activa por pedido;
   - columnas de versión y anulación en `sale`, con índice único parcial `WHERE voided_at IS NULL`.
4. **Reapertura/anexo/adición:** rechazar si la venta está despachada, impresa, en `PRINTING` o con `print_job` reclamado. Si aún no salió, permitir una revisión que anule y reemplace, sin borrar. Si ya salió, exigir un flujo explícito de corrección y reimpresión.
5. **`print_job`:** nunca borrar. Agregar estados/lease y cancelación solo antes de reclamar. Un trabajo enviado no se “desaparece”.
6. **Auditoría:** `audit_log` debe ser append-only para eventos comerciales; el reset no puede borrarla. Si se necesita retención, usar archivado, no truncado.
7. **Migración:** backup, migración aditiva, backfill de estados, luego constraints. No aplicar un `DELETE` masivo para “limpiar”.

**Criterios de aceptación**

- En producción, el endpoint de reset no existe o está bloqueado y auditado.
- Ningún flujo normal ejecuta `DELETE` sobre `sale`, `sale_item` o `print_job`.
- Reabrir un pedido impreso/despachado devuelve 409 y no altera la venta.
- Anexar una adición a un pedido con impresión en curso devuelve 409; no hay carrera.
- Una corrección genera un registro nuevo/enlazado, con motivo y actor; la venta anterior queda consultable.
- Pruebas de concurrencia: worker imprimiendo + reopen/merge/adición no borran ni duplican la venta.
- Un backup/restore de prueba conserva ventas y auditoría.

**Decisión de negocio requerida:** qué significa “corregir” una venta ya impresa/despachada: anular y reemplazar, nota de crédito, o pedido nuevo. No se debe codificar sin esa regla.

### 4.3 P0-03 — Autoaprobación insegura y cantidades inventadas

**Cambios**

1. **Default seguro:** `AUTO_PRINT_WHOLESALE=false` por defecto en `application.yml`, en la migración de `printer_setting` y en la UI. Habilitarlo debe ser una acción explícita de un ADMIN, con confirmación y auditoría.
2. **Compuerta única:** implementar un método `canAutoApprove(order, quote, printer)` que exija **todas** estas condiciones:
   - pedido en `REVIEW`, no cancelado/aprobado, versión vigente;
   - cliente explícitamente `WHOLESALE` (no “autoPrint=true”);
   - `reviewStatus=GREEN`, `aiStatus=OK`, `requiresVerification=false`;
   - al menos una línea; cada línea con `productId`, cantidad > 0, unidad válida, precio aplicable y subtotal;
   - `quote.complete()` y total no nulo;
   - impresora física habilitada y alcanzable; si no, el pedido va a revisión, no se marca como impreso;
   - sin `print_job` activo, sin despacho y sin venta previa;
   - sin dudas de IA, sin producto inventado, sin cantidad inferida.
3. **Quitar atajos:** `validateOrder` no puede llamar a `autoApproveWholesale`; solo debe marcar revisión humana. `reprintByOrderId` no puede autoaprobar un pedido sin venta; debe exigir aprobación manual.
4. **Cantidades:** eliminar el fallback a `BigDecimal.ONE` en `SaleService` y `OrderWriter`. Si falta cantidad, la línea queda incompleta y el pedido va a revisión/422. No se “adivina” 1.
5. **Precios:** no crear venta con `total`, `applied_price` o `subtotal` nulos. Si la cotización no está completa, no hay autoaprobación.
6. **Productos:** no autoinscribir productos desde la IA (ver P1-08). Un producto no resuelto deja la línea en revisión.
7. **Auditoría:** registrar regla/version, entradas, salida, actor (`ia_wholesale`/`ia_wholesale_timer`), pedido, venta y motivo. Un reintento debe ser idempotente.
8. **Pruebas primero:** escribir las pruebas negativas que fallen con el código actual y luego corregir.

**Criterios de aceptación**

| Caso | Resultado exigido |
|---|---|
| Pedido vacío | `REVIEW`, sin venta, sin `print_job`. |
| Línea sin cantidad | `REVIEW`/422, sin cantidad 1, sin venta. |
| `requires_verification=true` | Sin venta automática; revisión humana. |
| Cliente minorista | Sin autoaprobación. |
| `quote.complete()=false` | Sin venta; sin total nulo. |
| Producto inventado/ambiguo | Sin venta; línea en revisión. |
| Impresora deshabilitada | Sin `print_job`; pedido en revisión. |
| `print_job` activo | Sin segunda venta/impresión. |
| Versión obsoleta | 409, sin venta. |
| Pedido completo mayorista, impresora OK | Exactamente una venta y un `print_job`. |
| Doble llamada concurrente | Una sola venta; segunda llamada idempotente. |

**Riesgo de no hacerlo:** una alucinación o un mensaje ambiguo se factura, se imprime y se despacha sin humano; una cantidad faltante se convierte en 1; una venta queda sin papel.

## 5. Fase 2 — P1 (antes del piloto)

### 5.1 P1-01 — Webhook duradero y reintentable

**Cambios**

- Crear una bandeja de entrada durable (`whatsapp_event`) con payload crudo, clave de evento única, estado, intentos, error y timestamps.
- Verificar HMAC, insertar el evento y **responder 2xx solo después del commit**. Si la inserción falla, responder 5xx/503 para que Meta reintente.
- Procesar de forma asíncrona; el mensaje se persiste antes de llamar a IA/medios.
- Idempotencia por `external_message_id` y por clave de evento; duplicados responden 200 sin reprocesar.
- Retención, monitoreo de cola, alertas de eventos trabados y replay controlado.
- No depender de “Meta reintenta agresivamente” para justificar un 200 sin persistencia.

**Aceptación:** con PostgreSQL caído, el POST devuelve 5xx; al volver, Meta reintenta y se procesa una sola vez; un duplicado no crea otro pedido; un evento inválido queda registrado y alertado.

### 5.2 P1-02 — Validación y estados del webhook

- Validar `object=whatsapp_business_account`, `change.field`, `metadata.phone_number_id` contra el número configurado.
- Procesar `statuses`: `sent`, `delivered`, `read`, `failed`, con `wamid`, timestamps, errores y objeto `pricing` (categoría, billable, modelo).
- Ignorar reacciones, stickers, ubicaciones, contactos y tipos no soportados; no crear pedidos con texto placeholder.
- Validar que `contacts[].wa_id` corresponda al remitente; no confiar en nombres.
- Añadir opt-out/supresión y palabras de baja.
- Responder 400 para JSON malformado que pasó HMAC y alertar; no crear pedidos.

### 5.3 P1-03/P1-04 — Impresión honesta y sin carreras

- Estados separados: `PENDING`, `CLAIMED`, `SENDING`, `SENT_TO_PRINTER`, `ACKNOWLEDGED`, `FAILED`, `UNKNOWN`, `CANCELLED`.
- Lease con `claimed_by`, `lease_until`, `attempt_id`; no reencolar `PRINTING` sin timeout; no reimprimir un trabajo activo.
- Si la impresora soporta `DLE EOT`, leer estado; si no, **no afirmar impresión física**. Mostrar “enviado a impresora” y exigir confirmación humana para “impreso”.
- Nunca borrar `print_job`; reimpresión = trabajo nuevo, motivo, actor y auditoría.
- `reprintAllPending` no debe tocar trabajos reclamados; solo fallidos con lease vencido.
- UI: “aceptado por Meta”/“enviado a impresora”/“confirmado por operador”, no “impreso” sin evidencia.
- Pruebas con impresora simulada, falta de papel, red, reinicio y dos workers.

### 5.4 P1-05 — WhatsApp saliente: plantillas, ventana, opt-out y costos

- Catálogo de plantillas aprobadas: confirmación de pedido, pedido listo, despacho, factura (utility); marketing separado y solo con opt-in.
- Calcular la ventana de 24 h desde el último mensaje entrante. Si está cerrada, usar plantilla; no intentar texto libre y caer a un error.
- Persistir `wamid` de la respuesta de Meta (hoy se descarta el cuerpo) y correlacionar `statuses`.
- Ledger de mensajes salientes: orden, cliente, tipo, plantilla, categoría, `billable`, `pricing_type`, `wamid`, estados, reintentos, costo estimado.
- Opt-out persistente (BAJA/STOP/CANCELAR), confirmación y bloqueo de envíos; consentimiento registrado con fecha/fuente.
- Deduplicación persistente por `(order_id, kind, version)`; el reenvío manual exige confirmación y queda auditado; límite de frecuencia.
- Etiquetas UI: “aceptado por Meta” vs “entregado” vs “leído” vs “fallido”.
- No enviar factura y despacho como dos mensajes si se puede consolidar; medir el costo real.

### 5.5 P1-06 — Dependencias y cadena de suministro

- Plan de migración a Spring Boot 4.0.x / Spring Framework 7 / Security 7 / Tomcat 11 / PG JDBC 42.7.14, con Spring AI compatible (mínimo 1.0.9; validar 1.1.8/2.0.2).
- No hacer el upgrade a ciegas: branch, staging, suite completa, pruebas de contrato, carga y rollback.
- Mientras tanto: no exponer el backend directamente, limitar actuator, WAF/reverse proxy, revisar CVEs aplicables y aceptar formalmente el riesgo residual.
- SBOM (CycloneDX), OSV/Dependabot en CI, política de parches y alertas.

### 5.6 P1-07 — Bootstrap de usuarios y política de claves

- `BootstrapUsers` insert-only: no reescribir `password_hash` en cada reinicio.
- Cambio de contraseña explícito, con verificación de la clave actual, auditoría y revocación de sesiones.
- Mínimo 12–14 caracteres para usuarios nuevos; bloqueo por intentos, expiración/rotación y cambio inicial obligatorio.
- Un comando administrativo separado para resetear una clave, con confirmación y auditoría; nunca automático al arrancar.
- Evaluar si `OPERADOR` debe cambiar hosts/puertos de impresora; probablemente solo `ADMIN`.

### 5.7 P1-08 — Productos sugeridos por IA

- No crear productos activos automáticamente.
- Crear candidatos en `product_candidate` con nombre original, unidad propuesta, origen, confianza y estado `PENDING`.
- La línea del pedido queda en revisión hasta que un operador apruebe, corrija, fusione con un producto existente o descarte.
- Si se permite alta automática, limitarla a coincidencia exacta, unidad validada y auditoría; nunca a nombres ambiguos.
- Pruebas de alucinación, typo, sinónimo y unidad incorrecta.

### 5.8 P1-09 — Pruebas live fuera de la suite por defecto

- `@Tag("live")` y perfil Maven separado; excluir por defecto.
- No cargar `.env` automáticamente ni copiar claves a propiedades del sistema.
- Credenciales de prueba con límite de gasto; mock por defecto en CI.
- Documentar que la corrida de auditoría hizo 3 llamadas reales a Gemini; no repetir sin autorización.

### 5.9 P1-10 — PII, retención y backups

- Política de retención por tipo: pedidos/ventas (plazo legal/contable), audios/imágenes (mínimo necesario), transcripciones, logs y backups.
- Cifrado en reposo, control de acceso, borrado seguro y procedimiento de atención de titulares (Ley 1581 de 2012).
- Backup diario cifrado, fuera de sitio, con retención y **restore trimestral probado** en staging.
- RPO/RTO definidos; monitoreo de éxito/fallo de backups; runbook de restauración.
- No usar datos de producción en pruebas sin anonimización.

## 6. Fase 3 — Validación, piloto y compuerta de producción

### 6.1 Pruebas obligatorias antes del piloto

1. **Regresión P0:** todas las tablas de aceptación de P0-01/02/03 en verde.
2. **Integración:** PostgreSQL real, Testcontainers, migraciones desde una base con datos, concurrencia de dos workers.
3. **Contrato Meta:** WireMock para POST, HMAC, `statuses`, plantillas, errores 4xx/5xx, ventana y `wamid`.
4. **Impresión:** fake con estados y una prueba física con impresora real (papel, corte, red, reinicio).
5. **WhatsApp:** número de prueba, mensaje entrante, respuesta, plantilla, estado, opt-out y costo.
6. **Seguridad:** HMAC real, 403 por rol, CSRF, rate limit, replay, reset en prod, bootstrap, escaneo de secretos/dependencias.
7. **Carga/caos:** ráfagas, reintentos, caída de BD, caída de Meta, worker caído, reinicio y recuperación.
8. **Backup/restore:** restauración completa en staging con verificación de ventas, pedidos y auditoría.
9. **UAT:** operadores reales con pedidos de prueba; aceptación de reglas de revisión, corrección y reimpresión.

### 6.2 Piloto controlado

- Número de WhatsApp de prueba y un grupo cerrado de clientes con consentimiento.
- Una impresora real en un solo punto; `PRINTER_ENABLED=true` solo allí.
- Autoaprobación deshabilitada o limitada a un caso de negocio aprobado y monitoreado.
- Observabilidad: cola de webhooks, latencia IA, tasa de revisión, ventas, impresiones, fallos, mensajes/categorías y costo.
- Criterios de aborto: cualquier venta sin revisión, cualquier borrado, cualquier `PRINTED` sin papel, caída de webhook no recuperada o costo fuera de presupuesto.

### 6.3 Compuerta de producción

No se despliega a producción masiva hasta que:

- P0-01/02/03 estén cerrados con evidencia y pruebas de regresión.
- P1-01/03/05/10 estén cerrados o exista una aceptación formal de riesgo con mitigación.
- El piloto tenga métricas estables y UAT firmada.
- Backups/restore y rollback estén probados.
- Legal/privacidad y negocio aprueben retención, opt-out y corrección de ventas.
- No haya secretos en el repositorio ni rutas destructivas en prod.
- Exista runbook de incidentes y monitoreo/alertas.

## 7. Secuencia recomendada y dependencias

La complejidad es preliminar (S/M/L), no una fecha comprometida.

| Orden | Paquete | Depende de | Complejidad | Bloquea producción |
|---:|---|---|---|---|
| 0 | Contención, rotación, backup, freeze | Autorización | S/M | Sí |
| 1 | P0-01 secreto e historial | Rotación | M | Sí |
| 2 | P0-03 autoaprobación/cantidades | Decisión de negocio | M | Sí |
| 3 | P0-02 inmutabilidad/reset | Decisión contable + migración | L | Sí |
| 4 | P1-01 webhook duradero | P0-01 | M | Sí (piloto) |
| 5 | P1-02 validación/statuses | P1-01 | S/M | Sí (piloto) |
| 6 | P1-03/P1-04 impresión | Hardware/negocio | M/L | Sí (piloto) |
| 7 | P1-05 WhatsApp plantillas/opt-out | Meta/legal | L | Sí (piloto) |
| 8 | P1-07 bootstrap/claves | — | S | Sí |
| 9 | P1-08 productos IA | P0-03 | M | Recomendado |
| 10 | P1-09 tests live | — | S | Sí (CI) |
| 11 | P1-10 backups/PII | Operación/legal | M | Sí |
| 12 | P1-06 dependencias | Ventana de migración | L | Sí o aceptación formal |
| 13 | Fase 3 piloto/UAT | Todo lo anterior | L | Sí |

**Paralelización posible:** P1-07, P1-09 y la preparación de backups pueden avanzar mientras se decide P0-02/P0-03. La migración de dependencias (P1-06) puede ir en una rama separada, pero no debe mezclarse con los cambios P0.

## 8. Estrategia de regresión

### 8.1 Reglas

- **Primero la prueba que falla:** por cada P0, agregar una prueba que reproduce el defecto y falla con el código actual. No se acepta un fix sin esa evidencia.
- **Pruebas de negocio, no de implementación:** verificar que no hay venta/print_job, que la venta anterior sigue consultable, que el estado es el esperado.
- **Concurrencia real:** dos transacciones/workers, no solo mocks.
- **Migraciones:** probar desde V18 con datos representativos, no solo desde cero.
- **Contratos:** WireMock para Meta; fake printer para ESC/POS; Testcontainers para PostgreSQL.
- **Determinismo:** debounce 0 en tests, relojes controlados, sin dependencia de Internet.
- **Aislamiento:** live tests con tag/perfil; nunca en CI por defecto.

### 8.2 Pruebas P0 mínimas

| ID | Prueba | Resultado esperado |
|---|---|---|
| R-01 | Autoaprobar pedido vacío | Sin venta, sin print_job, estado REVIEW. |
| R-02 | Autoaprobar línea sin cantidad | Sin venta; no se usa 1; 422/REVIEW. |
| R-03 | Autoaprobar `requires_verification=true` | Sin venta automática; revisión. |
| R-04 | Autoaprobar cliente minorista | Sin venta automática. |
| R-05 | Autoaprobar cotización incompleta | Sin venta; sin total nulo. |
| R-06 | Autoaprobar con impresora deshabilitada | Sin print_job; revisión. |
| R-07 | Doble autoaprobación concurrente | Una sola venta; idempotente. |
| R-08 | `validateOrder` sobre pedido no aprobado | No crea venta. |
| R-09 | `reprintByOrderId` sin venta | No autoaprueba. |
| R-10 | Reopen de venta impresa/despachada | 409; venta intacta. |
| R-11 | Merge con print_job en PRINTING | 409; sin borrado. |
| R-12 | Adición a pedido aprobado impreso | Pedido nuevo/revisión; venta anterior intacta. |
| R-13 | Reset en perfil prod | 404/403; datos intactos. |
| R-14 | Webhook con BD caída | 5xx; reintento posterior procesa una vez. |
| R-15 | HMAC inválido / válido | 403 / 200; sin bypass en prod. |
| R-16 | Duplicado de mensaje | Un solo pedido. |
| R-17 | `statuses` de Meta | Estado y pricing persistidos. |
| R-18 | Reimpresión durante PRINTING | No duplica; lease respetado. |
| R-19 | Reinicio con PRINTING | Estado UNKNOWN/recuperación segura; sin doble envío ciego. |
| R-20 | Producto inventado | Candidato pendiente; no producto activo. |

### 8.3 CI mínimo

- `mvn -B verify` con unit + integración (Docker obligatorio) y reporte de omitidos.
- Frontend `node frontend-tests/operacion.test.js`.
- Gitleaks/TruffleHog.
- OSV/Dependabot y SBOM.
- Migraciones Flyway desde cero y desde snapshot.
- Cobertura JaCoCo publicada (sin umbral arbitrario al inicio).
- Live tests solo manuales, con etiqueta y presupuesto.

## 9. Despliegue y rollback

1. **Backup y verificación** antes de la migración.
2. **Migración aditiva** primero (columnas/tablas/índices), sin borrar datos.
3. **Despliegue del código** con feature flags en modo seguro (`AUTO_PRINT_WHOLESALE=false`, `DEV_ENDPOINTS=false`).
4. **Canary** con número de prueba/una impresora/usuarios internos.
5. **Verificación** de webhook, pedido, venta, impresión, WhatsApp y auditoría.
6. **Activación gradual** solo tras los criterios de aceptación.
7. **Rollback:** imagen anterior + flags seguros; migraciones compatibles hacia atrás; no revertir con `DELETE`/`TRUNCATE`. Si una migración no es reversible, restaurar backup verificado.

**Regla:** ninguna migración destructiva en la misma ventana que el despliegue inicial. La purga de historial Git es una operación separada y coordinada.

## 10. Decisiones que debe tomar el negocio

Sin estas respuestas, P0-02/P0-03 no se pueden cerrar correctamente:

1. ¿Se permite autoaprobar? Si sí, ¿solo mayoristas, solo pedidos completos y con qué impresora?
2. ¿Cómo se corrige una venta ya impresa/despachada: anulación, reemplazo, nota de crédito o pedido nuevo?
3. ¿Se permite reabrir un pedido después de imprimir/despachar? ¿Con qué autorización?
4. ¿La confirmación de impresión es por ACK del hardware o por confirmación humana?
5. ¿Qué mensajes se envían por WhatsApp y con qué consentimiento? ¿Marketing, transaccional o ambos?
6. ¿Cuál es la retención de pedidos, ventas, audios, imágenes y logs?
7. ¿Cuál es el presupuesto mensual de WhatsApp/IA y quién lo aprueba?
8. ¿Quién puede cambiar la configuración de impresora y la autoaprobación?
9. ¿Se acepta el riesgo residual de dependencias hasta completar Boot 4, o se retrasa el piloto?

## 11. Definición de terminado

- [ ] Secreto rotado; valor viejo inválido; sin secretos en árbol/historial o purga aprobada.
- [ ] Reset eliminado/bloqueado en prod; ventas inmutables; `print_job` no se borra.
- [ ] Autoaprobación deshabilitada por defecto y con compuerta completa; sin cantidades 1 ni ventas nulas.
- [ ] Reopen/merge/adición no destruyen ventas; concurrencia probada.
- [ ] Webhook durable, HMAC real, `statuses`, `phone_number_id` y tipos no soportados.
- [ ] Impresión con estados honestos, lease, reimpresión segura y prueba física.
- [ ] WhatsApp con plantillas, ventana, opt-out, `wamid`, estados y ledger de costo.
- [ ] Bootstrap insert-only; política de claves; sesiones revocadas.
- [ ] Productos IA en cuarentena.
- [ ] Backups cifrados, restore probado y retención/PII definidas.
- [ ] Dependencias actualizadas o riesgo aceptado formalmente.
- [ ] Suite determinista, sin live tests por defecto, con regresión P0 en verde.
- [ ] Piloto con métricas, UAT y rollback probado.

## 12. Qué no hacer

- No “arreglar” solo con `AUTO_PRINT_WHOLESALE=false`: los atajos `validateOrder`/`reprintByOrderId` siguen creando ventas.
- No borrar ventas para corregir; se pierde la trazabilidad.
- No purgar el historial Git sin coordinar clones, PRs, imágenes y backups.
- No rotar solo el app secret y dejar el access token/verify token viejos.
- No agregar plantillas sin opt-in ni revisar su categoría: marketing cuesta ~15,6 veces más que service en Colombia.
- No prometer impresión exactly-once sin ACK del hardware.
- No correr `ExtractionEvalTest` en CI ni repetirlo sin autorización.
- No actualizar Spring Boot a ciegas ni mezclar la migración con los P0.
- No usar la base de producción para pruebas de reset.
- No declarar “corregido” sin prueba de regresión y evidencia de staging.

## 13. Autorización requerida

Para ejecutar este plan se necesita autorización explícita para:

1. **Fase 0:** rotar credenciales, congelar autoaprobación/impresión y tomar backup.
2. **P0:** modificar código, migraciones y configuración; agregar pruebas de regresión.
3. **Purga de historial Git:** operación destructiva con force-push y re-clonado; requiere ventana y coordinación.
4. **Pruebas live:** repetir llamadas a Gemini/Meta y usar hardware/número de prueba.
5. **Alcance:** ¿solo P0, P0 + P1, o una lista de IDs? ¿Se incluye la migración de dependencias?

Sin esas definiciones, este documento queda como plan y no como autorización de cambio.
