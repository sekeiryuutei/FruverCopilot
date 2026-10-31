# Auditoría de seguridad — FruverCopilot

**Fecha:** 2026-10-25  
**Commit auditado:** `dc5d842` (rama `mayorista`)  
**Tipo de revisión:** estática, revisión de configuración, consulta OSV y pruebas locales seguras. No fue un pentest ni una prueba de intrusión.

## 1. Resumen

La aplicación tiene controles positivos: autenticación por sesión, CSRF, roles, HMAC del webhook, comparación en tiempo constante, idempotencia por mensaje y consultas con parámetros. Sin embargo, **no está lista para exponerse a Internet ni para operar con clientes reales** por tres motivos P0:

- un secreto de WhatsApp vigente está versionado y presente en el historial;
- existen rutas que borran ventas, pedidos, mensajes y auditoría en producción;
- la autoaprobación puede crear una venta y una comanda sin revisión humana, incluso con dudas, pedido vacío o cantidades incompletas.

La revisión de dependencias también muestra vulnerabilidades conocidas que requieren una actualización mayor coordinada.

## 2. Metodología

1. Revisión del árbol de código en `backend/src/main/java`, `backend/src/main/resources`, migraciones Flyway V1–V18, configuración y pruebas.
2. Comparación programática (sin imprimir valores) entre el secreto referenciado en la documentación y el `.env` actual.
3. Revisión de superficies HTTP y de las autorizaciones `@PreAuthorize`.
4. Revisión de la configuración de producción (`application-prod.yml`, `ProductionGuard`, Dockerfile, Compose).
5. Consulta puntual a OSV el 2026-10-25 para las versiones efectivas del `pom.xml` y dependencias transitivas conocidas.
6. Ejecución local de pruebas seguras; no se enviaron mensajes ni se imprimió en hardware.

No se accedió a la base de producción, no se extrajeron clientes reales y no se publicó ningún secreto.

## 3. Riesgos P0

### P0-01 — Secreto de WhatsApp comprometido y versionado

**Hecho comprobado**

- `AUDITORIA.md` está versionado en Git (`git ls-files` lo confirma).
- Ese archivo contiene un secreto de WhatsApp que **coincide exactamente** con el `WHATSAPP_APP_SECRET` actual del `.env` (comparación programática, sin mostrar el valor).
- El archivo está presente en `origin/main` y `origin/mayorista`; el commit `d895c2e` lo incorporó y está publicado en ambas ramas.
- El token de acceso de WhatsApp no aparece en ese documento, pero el app secret sí.
- `.dockerignore` excluye `*.md`, por lo que probablemente no se copia a la imagen; eso no elimina la exposición en el repositorio, los clones ni el historial.

**Por qué es P0**

El app secret es la clave con la que Meta firma los webhooks. Quien lo tenga puede construir un POST con una firma HMAC válida para `/webhook/whatsapp`. Si el atacante conoce el número de teléfono de un cliente o lo inventa, puede inyectar mensajes y pedidos. Con la autoaprobación activa, esos pedidos pueden convertirse en ventas y trabajos de impresión sin intervención humana. No se necesita el token de acceso para el webhook; el impacto es de integridad y costo, y podría escalar a fraude si el flujo imprime o marca ventas.

**Acciones obligatorias**

1. Rotar **inmediatamente** el app secret en Meta y actualizar el despliegue.
2. Rotar también el token de acceso y el verify token por precaución operativa, y revisar actividad reciente en Meta Business.
3. Eliminar el valor de `AUDITORIA.md` y de cualquier otro documento; reemplazarlo por una referencia a variable de entorno.
4. Purgar el secreto del historial Git (todas las ramas y tags). Esto requiere coordinar un `filter-repo`/BFG, force-push y re-clonado; es una operación destructiva que **no se ejecutó**.
5. Confirmar que ningún backup, imagen Docker, log o copia local conserve el valor antiguo.
6. Añadir un escaneo de secretos (Gitleaks/TruffleHog) en pre-commit y CI.

**Criterio de aceptación:** el valor viejo ya no autentica, no aparece en el árbol actual, no aparece en el historial publicado y el escaneo de secretos pasa.

### P0-02 — Borrado destructivo de información comercial

#### 2.1 Reset global disponible en producción

`backend/src/main/java/com/fruver/interfaces/rest/SaleController.java:323-331`:

```java
@PostMapping("/sales/reset-test-data")
@PreAuthorize("hasRole('ADMIN')")
public Map<String, Object> resetTestData() {
    jdbc.sql("TRUNCATE TABLE print_job, sale_item, sale, order_item, conversation_message, audio, transcription, media_image, audit_log, orders RESTART IDENTITY CASCADE").update();
    ...
}
```

- No está condicionado al perfil `prod`, a `DEV_ENDPOINTS`, a un token de confirmación ni a una ventana de mantenimiento.
- Un ADMIN comprometido, una sesión robada o un error humano puede borrar toda la operación y la auditoría.
- La respuesta HTTP no obliga a un respaldo previo ni deja evidencia (porque también borra `audit_log`).

**Acción:** eliminar la ruta del build de producción o bloquearla con `@Profile("!prod")` y una confirmación explícita; si se necesita para demo, usar un contenedor/base separada.

#### 2.2 Borrado de venta y `print_job` en operaciones normales

Evidencia:

- `OrderIntakeService.java:63-71`: al recibir una nueva adición de un cliente con pedido aprobado no impreso, borra `print_job`, `sale_item` y `sale` para volver a procesar.
- `OrderEditService.java:300-306`: al anexar un pedido a otro con venta, borra los mismos registros.
- `OrderEditService.java:365-370`: `reopen` borra la venta y los trabajos de impresión sin comprobar estado, despacho, pago, impresión en curso ni auditoría.
- `SaleService.java:340-347`: `reprintByOrderId` puede llamar a `autoApproveWholesale` si no hay venta, incluso para un pedido cancelado o no aprobable.
- `SaleFlowIntegrationTest.java:407-427` valida explícitamente que `reopen` deja `saleId=null`; es decir, la prueba protege el comportamiento destructivo en lugar de impedirlo.

**Impacto:** se pierde el registro financiero de una venta ya aprobada; una comanda que ya salió en papel puede quedar sin venta asociada; el worker de impresión puede tener un `print_job` reclamado en `PRINTING` mientras otro proceso borra la venta; el cliente puede recibir una comanda duplicada o no recibir ninguna. También se rompe la trazabilidad para auditoría, cartera y reportes.

**Acción:** las ventas deben ser inmutables una vez creadas. Para corregir, usar una venta de reemplazo o un estado `VOIDED`/`SUPERSEDED` con motivo, actor, versión y relación con la venta anterior. Nunca borrar `sale`, `sale_item` ni `print_job` desde el flujo de conversación. La reapertura debe rechazarse si hay despacho o impresión en curso, o crear un nuevo pedido/venta claramente enlazado. Antes de cambiar, definir la regla contable con el negocio.

### P0-03 — Autoaprobación que ignora la revisión y crea ventas inválidas

Evidencia:

- `OrderProcessingService.java:272-288` decide autoaprobar si `autoPrint` está activo, sin comprobar `io.review()`, `requires_verification` ni el tipo de cliente.
- `OrderProcessingService.java:302-343` hace lo mismo desde el temporizador cada 10 s; un pedido vacío cuenta como “dudoso” pero aun así se llama a `autoApproveWholesale`.
- `SaleService.java:151-217` no comprueba el estado del pedido, no vuelve a validar el `quote.complete()` para crear la venta, no exige `WHOLESALE`, no exige `PRINTER_ENABLED=true` y persiste el `requires_verification=true` mientras crea la venta y el `print_job`.
- `SaleService.java:188-190` y `OrderWriter.java:175-177` sustituyen una cantidad ausente o no positiva por `BigDecimal.ONE`.
- `application.yml:82`: `auto-print-wholesale: ${AUTO_PRINT_WHOLESALE:true}`. En el `.env` observado la variable no está definida, así que aplica `true`.
- `WholesaleFlowIntegrationTest.java:106-127` demuestra el problema: espera que el pedido quede `APPROVED` y luego solo comprueba que `requires_verification=true`. La prueba pasa con el comportamiento incorrecto.
- `SaleController.java:136-153` (`validateOrder`) también puede invocar `autoApproveWholesale` y crear venta para un pedido no aprobado con un solo clic, sin versión optimista.

**Impacto:** una interpretación dudosa o una alucinación de IA se factura como cierta; una cantidad faltante se convierte en 1; un pedido sin líneas genera una venta vacía; un pedido de cliente minorista puede autoaprobarse; con `PRINTER_ENABLED=false` la venta se marca como “impresa” contra la impresora simulada.

**Acción:** desactivar la autoaprobación por defecto y, antes de habilitarla, exigir todas estas condiciones: cliente `WHOLESALE`, `reviewStatus=GREEN`, `requires_verification=false`, al menos una línea con producto/precio/cantidad completos, `quote.complete()`, pedido en `REVIEW`, versión vigente, impresora física habilitada y ningún trabajo de impresión en curso. La incertidumbre debe ir a revisión humana, nunca a una venta automática.

## 4. Riesgos P1

> Los identificadores P1 siguen la tabla del resumen ejecutivo. El orden de las secciones agrupa por tema, no por número.

### P1-01 — El webhook confirma 200 aunque falle la ingesta

`WhatsAppWebhookController.java:68-82` captura cualquier excepción y retorna 200:

```java
try {
    ... handle(m, names);
} catch (Exception e) {
    log.error("No se pudo procesar el webhook de WhatsApp", e);
}
return ResponseEntity.ok().build();
```

Si la base de datos está momentáneamente caída o falla una escritura antes de persistir el mensaje, Meta recibe 200 y no reintenta. El pedido se pierde. La justificación del comentario (“Meta reintenta agresivamente”) es válida solo si el 200 significa que ya se persistió o que el evento es duplicado.

**Acción:** persistir el evento crudo (outbox/inbox durable) antes de confirmar; devolver 2xx solo si quedó registrado; devolver 5xx/429 ante fallo transitorio; usar el `external_message_id` para idempotencia. Procesar asíncronamente y medir la cola.

### P1-02 — Validación incompleta del webhook

- No se verifica `change.field`, el objeto `whatsapp_business_account` ni `metadata.phone_number_id` contra el número configurado (`WhatsAppWebhookController.java:68-76`).
- No se procesan `value.statuses`; por tanto no hay confirmación de entrega, lectura, fallo, precio/categoría ni estado de cuenta.
- El `default` de `handle` convierte cualquier tipo no soportado en texto `"[Mensaje de tipo ... no soportado]"` (`:108`) y crea un pedido. Una reacción, sticker, ubicación o contacto puede crear un pedido vacío.
- Se aceptan `contacts` y `messages` sin validar que el `wa_id` coincida con el remitente.
- No hay manejo de opt-out ni de palabras de baja.

**Acción:** aceptar solo tipos conocidos (text/audio/image), ignorar reacciones y eventos sin pedido, validar el número receptor, persistir estados y agregar supresión/opt-out.

### P1-05 — WhatsApp saliente sin plantillas ni ventana de 24 h

`WhatsAppCloudClient.java:38-48` solo envía mensajes de tipo `text`; `sendImage` intenta imagen y cae a texto (`:50-68`). No hay:

- envío de plantillas aprobadas;
- cálculo de la ventana de 24 h;
- procesamiento del webhook de estados;
- identificación de mensajes por `wamid` para conciliación;
- baja/opt-out;
- registro de consentimiento.

Fuera de la ventana de 24 h, Meta rechaza mensajes no-plantilla. El código convierte el fallo en `FAILED` o lo ignora; no hay reintento programado ni aviso operativo fiable. `DeliveryStatus.SENT` se asigna tras un HTTP 2xx, no tras entrega real (`:44` y `:64`). La interfaz puede mostrar “enviado” aunque Meta haya aceptado y luego no entregado.

**Acción:** implementar catálogo de plantillas utilitarias, cola de envío con estados, webhook `statuses`, conciliación por `wamid`, opt-out persistente y una política explícita de la ventana. Etiquetar en UI “aceptado por Meta” vs “entregado”.

### P1-03 — Semántica falsa de impresión

`PrintService.java:60-79` marca `DONE/PRINTED` después de `printer.print(payload, location)`. `NetworkEscPosPrinter.java:51-57` solo abre un socket TCP, escribe, hace flush y cierra. No consulta estado ESC/POS, no lee `DLE EOT`, no verifica papel, tapa, corte ni buffer. Si el paquete se pierde después del `flush`, el sistema igual marca `PRINTED`. Tampoco hay confirmación de corte ni de apertura de cajón.

**Acción:** separar estados `QUEUED`, `CLAIMED`, `SENT_TO_PRINTER`, `ACKNOWLEDGED`, `FAILED`, `UNKNOWN`; usar lease/`attempt_id`; si el hardware no ofrece ack, declarar la limitación y exigir confirmación humana para “impreso”. No prometer exactly-once.

### P1-04 — Carreras destructivas y reimpresión

- no hay bloqueo que impida borrar venta/print_job mientras el worker tiene `status='PRINTING'`;
- `reprintAllPending` reencola también `PRINTING` (`PrintService.java:83-90`), lo que puede duplicar una impresión en curso;
- `reprint` cambia la venta a `PRINT_PENDING` sin CAS sobre el estado anterior (`SaleService.java:320-330`);
- un reinicio después del `flush` puede dejar `PRINTING` y reclamarse a los 2 minutos, duplicando la salida.

**Acción:** no borrar ventas; no reencolar `PRINTING` sin timeout; usar lease con vencimiento y `attempt_id`; exigir confirmación humana cuando el hardware no permita ack. La reimpresión debe ser un evento nuevo, auditado y con motivo, nunca un borrado silencioso.

### P1-06 — Dependencias con CVEs conocidos

Revisión OSV del 2026-10-25. Las versiones efectivas incluyen:

| Componente | Versión observada | Situación |
|---|---:|---|
| Spring Boot | 3.4.5 | `CVE-2026-40973` (HIGH) sin corrección en 3.4.x, según la revisión. |
| Spring Framework | 6.2.6 | Avisos, incluidas correcciones críticas que, según OSV, solo llegan a 7.0.9 (`CVE-2026-47890`, `CVE-2026-47884`). |
| Spring Security | 6.4.5 | Avisos pendientes de la línea 6.4. |
| Tomcat embebido | 10.1.40 | Varios avisos, entre ellos `CVE-2026-43515`, `CVE-2026-65905`, `CVE-2026-65182`, `CVE-2026-68525`, `CVE-2026-41293`. |
| PostgreSQL JDBC | 42.7.5 | Avisos; actualizar a 42.7.14 o superior. |
| Spring AI | 1.0.0 | Avisos; corregido desde 1.0.7, con 1.0.9/1.1.8/2.0.2 disponibles. |

**Ruta recomendada:** evaluar y migrar a Spring Boot 4.0.9 (Tomcat 11.0.27, Spring 7.0.10, Security 7.0.8, PG JDBC 42.7.14) con Spring AI compatible (mínimo 1.0.9; validar 1.1.8/2.0.2). No se verificó compatibilidad de Spring AI con Boot 4; es un trabajo de migración con pruebas, no un cambio de versión a ciegas.

**Mitigaciones temporales:** no exponer el backend directamente; limitar actuator a health; proxy con límites de request; WAF; revisar CVEs aplicables; acelerar la migración. Un upgrade de Tomcat a 10.1.58 reduce parte del riesgo, pero no corrige el CVE de Boot 3.4.5.

### P1-07 — Contraseñas de bootstrap y política de usuarios

`BootstrapUsers.java:54-58` hace:

```sql
INSERT INTO app_user(...) VALUES (...)
ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash
```

Cada reinicio reescribe el hash del admin/operador con la contraseña de `.env`. Si un ADMIN cambia su contraseña por la API, el siguiente reinicio la revierte. Si el `.env` queda accesible, el cambio no sirve. Además, `UserController.java:18-26` permite contraseñas de 6 caracteres para usuarios nuevos, y `UserService.java:37-58` replica ese mínimo.

**Acción:** el bootstrap debe ser insert-only; el cambio de contraseña debe ser explícito y sobrevivir reinicios. Elevar el mínimo a 12–14 caracteres, con bloqueo/expiración y auditoría. Forzar cambio inicial y revocar sesiones al cambiar contraseña.

### P1-10 — Datos personales, retención y backups

- Se almacenan teléfonos completos, nombres, direcciones, notas, audios y fotos. No hay política de retención ni borrado por solicitud.
- `backups/backup.sh` y `backup.bat` existen, pero el directorio de backups solo contiene los scripts: no hay evidencia de una copia reciente, programación, cifrado, fuera de sitio ni restore probado.
- La documentación reconoce la Ley 1581 de 2012, pero no hay implementación de habeas data.
- Los audios se guardan en volumen Docker; no hay lifecycle ni cifrado en reposo declarado.

**Acción:** definir retención (por ejemplo, pedidos y PII durante el plazo legal/contable, audios el mínimo necesario), borrado seguro, backups cifrados con retención y restore trimestral en staging, y un procedimiento de atención de titulares.

### P1-08 — Auto-registro de productos sugeridos por la IA

`ProductCatalogService.java:64-90` crea un producto activo cuando la IA devuelve un nombre que no existe en el catálogo. La unidad se infiere de la respuesta del modelo (`LB`, `UNIDAD` o `KG`) y el producto queda disponible para futuras ventas sin revisión humana. Una alucinación, un error de transcripción o una unidad equivocada contamina el catálogo y puede producir una venta con producto o unidad incorrectos.

**Acción:** no crear productos activos desde la IA. Registrar candidatos en una tabla de revisión con nombre original, unidad propuesta, origen y confianza; dejar la línea del pedido en revisión hasta que un operador apruebe, corrija o descarte. Si el negocio exige alta automática, limitarla a coincidencias exactas y auditadas, nunca a nombres ambiguos o inventados.

### P1-09 — Pruebas que usan credenciales y servicios reales

`ExtractionEvalTest.java:39-59` lee `../.env` o `.env` y copia todas las claves a propiedades del sistema. El test se habilita si existe una clave real (`:32-37`) y hace llamadas a Gemini (`:66-70`). En la corrida de `mvn test` se ejecutaron **3 llamadas reales**, sin enviar WhatsApp ni imprimir. Esto es un riesgo de costo y de fuga de datos de prueba a un proveedor externo.

**Acción:** separar los tests live en un perfil/tag explícito, no cargar `.env` automáticamente, usar credenciales de test con límite de gasto y ejecutar solo bajo autorización. En CI, mock por defecto.

## 5. Superficie HTTP y autorización

| Ruta | Control observado | Evaluación |
|---|---|---|
| `/webhook/whatsapp` GET/POST | Público; verificación de token/HMAC | Correcto en mecanismo; ver P0-01, P1-01/P1-02. |
| `/login` | Público; rate limit por usuario+IP, auditoría | Mejoró respecto de la auditoría anterior; validar bypass y bloqueo de sesión. |
| `/api/orders/**` | `@PreAuthorize` ADMIN/OPERADOR a nivel de clase | Correcto en rol; falta prueba de integración de 403. |
| `/api/sales/**` | Roles en su mayoría; reset solo ADMIN | El reset es P0. |
| `/api/users/**` | Solo ADMIN; mínimo 6 caracteres | P1-07. |
| `/api/printer/**` | ADMIN/OPERADOR | OPERADOR puede cambiar hosts/puertos de impresora; evaluar si debe ser solo ADMIN. |
| `/actuator/health/**` | Público | Aceptable si no expone detalles; `/info` y `/metrics` requieren ADMIN. |
| `/api/dev/**` | Condicionado a `DEV_ENDPOINTS=true` | En el `.env` actual está activo y con perfil `dev`. No debe existir en prod. |

**Producción:** `ProductionGuard` bloquea `DEV_ENDPOINTS`, seed, mock, cookie insegura y secretos débiles **solo si** `SPRING_PROFILES_ACTIVE=prod` (`ProductionGuard.java:17`). Un despliegue con perfil `dev` desactiva todas esas barreras. No hay una comprobación redundante en el arranque ni en el reverse proxy.

## 6. Matriz de amenazas resumida

| Amenaza | Vector | Control actual | Riesgo residual |
|---|---|---|---|
| Webhook forjado | App secret filtrado + HMAC | HMAC, pero secreto expuesto | **P0** hasta rotar/purgar. |
| Inyección de pedido | Webhook válido con texto inventado | Autoaprobación | **P0**; puede crear venta/impresión. |
| Pérdida de pedido | Falla de BD, webhook responde 200 | Retorno 200 indiscriminado | **P1**. |
| Fraude interno | ADMIN usa reset/reopen | Rol ADMIN | **P0**; no hay doble control ni inmutabilidad. |
| Comanda perdida | TCP sin ack | `PRINTED` optimista | **P1**; no hay exactly-once. |
| Suplantación de cliente | Teléfono spoofing/WhatsApp | Meta verifica origen | Depende de Meta y del opt-in; no hay controles adicionales. |
| Fuga de PII | Logs, backups, proveedores IA | Enmascarado parcial; DPAs no verificados | **P1**; revisar retención y proveedores. |
| DoS/costo IA | Webhook válido o reintentos | Debounce, límites de body | **P1/P2**; no hay cuota por cliente ni presupuesto. |
| Fuerza bruta login | `/login` | Rate limit 5/10 min | **P2**; verificar resiliencia y bloqueo de cuentas. |

## 7. Controles positivos verificados

- Firma HMAC-SHA256 y comparación constante (`MessageDigest.isEqual`) para el cuerpo; verificación de token con comparación constante.
- Rechazo de webhook sin secreto o firma inválida; límite de cuerpo de 512 KB.
- Consultas SQL con parámetros nombrados; no se detectó SQLi en las rutas revisadas.
- Roles a nivel de controlador en pedidos, ventas y usuarios.
- CSRF con cookie y cabecera para `/api`; webhook y login excluidos correctamente.
- Sesión con `HttpOnly`, `SameSite=Lax`, `Secure` en el perfil prod; migración de sesión y máximo de 2.
- Login con bloqueo por intentos y auditoría de éxito/fallo.
- `sale.order_id UNIQUE`, `print_job` como outbox, bloqueo `FOR UPDATE` en aprobación manual.
- Frontend sin `innerHTML` para datos del pedido, según revisión del código y pruebas de DOM.
- `SimulatedPrinter` evita volcar PII en el log de información; el detalle queda en debug con enmascarado parcial.

## 8. Prioridades de seguridad

### Antes de cualquier exposición

1. Rotar app secret, access token y verify token.
2. Desactivar/eliminar reset y autoaprobación.
3. Bloquear cualquier compilación de producción con perfil distinto de `prod` o con guard faltante.
4. Congelar uso con clientes hasta corregir P0.

### Antes de un piloto controlado

5. Webhook duradero y estados.
6. Inmutabilidad de ventas y semántica de impresión.
7. Bootstrap de usuarios insert-only y política de claves.
8. Backup/restore probado y retención de PII.
9. Actualización de dependencias o aceptación formal del riesgo residual.
10. Pruebas de seguridad: HMAC real, 403 por rol, replay, duplicados, fallo de BD, opt-out y fuera de ventana.

### Antes de producción

11. Pentest externo o revisión independiente.
12. WAF/reverse proxy, TLS, límites, monitoreo y alertas.
13. Escaneo continuo de dependencias y secretos.
14. Runbook de incidentes y rotación.

## 9. Estado y limitaciones

- **No se rotaron credenciales** ni se modificó el historial.
- **No se probó** la autenticación HMAC con el secreto real y un payload real de Meta.
- **No se probó** el comportamiento bajo caída de PostgreSQL ni la recuperación de webhooks.
- **No se probó** el acceso desde Internet, WAF, TLS ni proxy.
- **No se probó** la compatibilidad de Boot 4/Spring AI; la tabla de versiones es una ruta a validar.
- La consulta OSV es una foto del 2026-10-25, no un análisis completo de composición de software.
- Los CVEs y versiones pueden cambiar; antes de ejecutar el plan, repetir la consulta y revisar avisos aplicables.

**Conclusión:** el diseño tiene bases defendibles, pero la combinación de secreto expuesto + rutas destructivas + autoaprobación insegura es incompatible con un entorno de producción. La seguridad no se puede declarar “corregida” sin rotación, pruebas de regresión y evidencia de hardware/WhatsApp.
