# Reauditoría FruverCopilot — 2026-10-31

**Repositorio:** `C:\Users\usuario\Documents\trabajos\FruverCopilot\FruverCopilot`  
**Rama:** `mayorista`  
**HEAD auditado:** `a29106e` (`Actualizo documentación con especificaciones de impresión láser A5, túnel 24/7 y pruebas de IA`)  
**Commit anterior de referencia:** `b58f922`  
**Modo:** auditoría de solo lectura. No se modificó código, configuración, credenciales ni historial Git. No se enviaron mensajes reales de WhatsApp y no se imprimieron pedidos reales.  
**Alcance:** código de `HEAD`, migraciones, configuración, pruebas, documentación, scripts operativos y estado del contenedor local. Las pruebas live de WhatsApp, impresora física, backup/restore y concurrencia real no se ejecutaron.

> **Veredicto: NO APTO para producción ni para un piloto abierto a clientes.**
>
> Los P0 de la auditoría del 2026-10-25 siguen presentes. Además, el piloto está públicamente accesible con perfil `dev`, `DEV_ENDPOINTS=true` y `COOKIE_SECURE=false`, lo que convierte varias fallas de diseño en riesgos explotables desde Internet.

---

## 0. Resumen ejecutivo

| Severidad | Cantidad | Temas |
|---|---:|---|
| P0 | 5 | Secreto vigente en Git; borrados/resets destructivos; autoaprobación e impresión sin filtros; XSS almacenado con cadena al reset; piloto público en perfil `dev` |
| P1 | 9 | Falso “impreso”, checkbox de impresora ignorado, mensajes fuera de ventana/plantilla, inyección HTML en el rasterizador, catálogo/cantidad inventados, webhook que oculta fallos, desactivación de productos por substring, túnel no 24/7, suite de pruebas y documentación inconsistentes |
| P2 | 5 | PII en logs, scripts de calibración duplicados, documentación desactualizada, ruido de `.gitignore`, supuestos de hardware no verificados |

**Cambios desde la auditoría anterior:** el código de producción no cambió entre `b58f922` y `a29106e`; el único cambio de `a29106e` es `DOCUMENTACION.md` (+31 líneas sobre IPP, túnel y pruebas). Por eso las referencias de código de este informe son las mismas que las del corte anterior.

**Lo que sí está bien:** la cadena principal de seguridad de las rutas de negocio usa CSRF, sesión `HttpOnly`/`SameSite`, roles en varios controladores, rate limit de login y `ProductionGuard` para `prod`; las consultas SQL están parametrizadas; existe idempotencia en la aprobación manual; y el contenedor local está healthy. El problema es que esas protecciones no cubren `/api/dev/**` en `dev`, ni la autoaprobación, ni la impresión.

---

## 1. Hallazgos P0

### P0-01 — Secreto de WhatsApp vigente en un archivo versionado y en el historial

**Evidencia**

- `AUDITORIA.md:47` contiene el `WHATSAPP_APP_SECRET` y coincide con el valor vigente de `.env`. El valor no se reproduce en este informe.
- `AUDITORIA.md` está versionado en `HEAD` y aparece en el commit histórico `d895c2e`.
- `.gitignore:1` ignora `.env`, pero no `AUDITORIA.md`; por tanto, borrar el archivo del árbol de trabajo no elimina el secreto del historial.
- El mismo documento contiene material de token de WhatsApp; por precaución debe rotarse también el access token, aunque el fragmento visible pueda estar truncado.
- `WhatsAppWebhookController` valida la firma HMAC con ese `appSecret`; si el secreto es válido, el atacante puede fabricar payloads que parezcan de Meta.

**Impacto**

Un tercero con el secreto puede firmar webhooks falsos. Como el webhook no valida `phone_number_id` y la autoaprobación no filtra cliente ni estado (P0-03), puede inyectar pedidos de números arbitrarios, hacer que se autoaprueben y que se impriman. También puede consultar/alterar el flujo si obtiene una sesión. Es un compromiso de integridad, no solo una filtración.

**Corrección**

1. Rotar `WHATSAPP_APP_SECRET` y el access token en Meta; invalidar los anteriores.
2. Eliminar el secreto de `AUDITORIA.md`; sustituirlo por una referencia a un gestor de secretos.
3. Purgar el historial con `git filter-repo` o BFG, incluyendo todas las ramas y tags; force-push coordinado. Esto es destructivo y requiere autorización expresa.
4. Revisar imágenes Docker, backups, logs y artefactos de CI que hayan copiado el archivo.
5. Añadir un escáner de secretos en CI y una prueba que falle si aparece un patrón de token en el repositorio.

---

### P0-02 — Borrados y resets destructivos sin protección suficiente

**Evidencia**

- `DevController.java:42-44`: `POST /api/dev/database/reset` ejecuta un `TRUNCATE` de `print_job`, `sale_item`, `sale`, `order_item`, `conversation_message`, `audio`, `transcription`, `media_image`, `audit_log` y `orders`.
- `SaleController.java:377-380`: `POST /api/sales/reset-test-data` hace el mismo `TRUNCATE`; está protegido solo por `hasRole('ADMIN')`.
- `DevSecurityConfig.java:23-26`: para `/api/dev/**` desactiva CSRF y usa `anyRequest().authenticated()`. Cualquier usuario autenticado, incluido `OPERADOR`, puede llamar los endpoints de desarrollo; no hay rol mínimo ni restricción de red.
- `OrderIntakeService.java:68-70`: al recibir un mensaje de un cliente con un pedido aprobado reciente, elimina `print_job`, `sale_item` y `sale` para volver a armarlo.
- `OrderEditService.java:303-305` y `367-369`: al anexar o reabrir, elimina `print_job`, `sale_item` y `sale`.
- `OrderEditService.java:371-379`: `reopen` deja el pedido en `REVIEW`, fuerza `review_status='GREEN'` y borra la venta previa, sin verificar si la venta ya fue impresa o despachada.
- `OrderEditService.java:287-297`: el merge solo bloquea si el destino ya fue despachado; no bloquea si la venta ya se imprimió.
- `SaleService.java:135` crea el `print_job` incluso si la impresora está deshabilitada o no existe.
- `OrderController.java:23`: el controlador de pedidos permite `ADMIN` y `OPERADOR`; un operador puede ejecutar reaperturas y merges.

**Impacto**

Un clic equivocado, una sesión comprometida o el XSS de P0-04 puede borrar ventas, ítems, jobs de impresión y hasta `audit_log` sin trazabilidad. Reabrir un pedido despachado elimina su venta; un merge puede borrar una venta ya impresa. El `TRUNCATE` de `audit_log` destruye precisamente la evidencia necesaria para investigar el incidente.

**Corrección**

- Eliminar `/api/dev/**` del perfil público y de `prod`; si se conserva, exigir `ADMIN`, CSRF, confirmación explícita, red local/VPN y un flag separado que no pueda activarse por accidente.
- Prohibir el borrado físico de ventas: usar `CANCELLED`, `VOID` o soft-delete con auditoría.
- En `reopen`/merge, bloquear si `sale.print_status IN ('PRINTED','PRINTING')` o si `orders.dispatched_at IS NOT NULL`. Nunca resetear `review_status` a `GREEN` sin recalcular.
- No truncar `audit_log` desde endpoints de aplicación; la purga de datos debe ser una operación administrativa fuera de línea, con backup y doble confirmación.
- Añadir pruebas de autorización y de “no borrar si ya se imprimió/despachó”.

---

### P0-03 — Autoaprobación y autoimpresión sin filtros de cliente, estado, revisión o impresora

**Evidencia**

- `application.yml:82`: `auto-print-wholesale: ${AUTO_PRINT_WHOLESALE:true}`. En el entorno actual `AUTO_PRINT_WHOLESALE` no está definido; el valor efectivo es `true`.
- `OrderIntakeService.java:42`: usa `props.printing().autoPrintWholesale()` para decidir si un pedido `APPROVED` no impreso puede seguir recibiendo mensajes. No consulta `customer_type`; el flag global se aplica a todos los clientes.
- `OrderProcessingService.java:272-287`: tras la extracción, si `autoPrint` está activo, llama a `sales.autoApproveWholesale(...)` inmediatamente o programa la aprobación diferida.
- `OrderProcessingService.java:304-338`: el timer busca pedidos en `REVIEW`, pero solo filtra `requires_verification`; no filtra `customer_type`, no exige que el pedido sea mayorista y no verifica impresora.
- `SaleService.java:153-210`: `autoApproveWholesale` no comprueba estado del pedido, tipo de cliente, `quote.complete()`, cantidad de ítems ni `printerConfig.enabled()`. Crea venta y `print_job` incluso con pedido vacío, producto desconocido o total nulo.
- `SaleService.java:202-209`: escribe `requires_verification` y `verification_reason`, pero deja el pedido en `APPROVED` y encola la impresión. La marca de duda es informativa, no bloqueante.
- `OrderProcessingService.java:277-280`: `hasTrulyDoubtfulLines` solo considera dos casos: cantidad totalmente ausente o línea ambigua sin `product_id`. No mira `io.review()` ni el caso “ambiguo con producto resuelto”.
- `SaleService.java:82`: la aprobación manual considera `isWholesale = customerType == WHOLESALE || autoPrintWholesale`. Con el default `true`, cualquier pedido minorista se trata como mayorista y se salta la validación de cotización completa.
- `SaleService.java:113` y `190`: si falta la cantidad, usa `BigDecimal.ONE`; el pedido se crea con 1 unidad/libra inventada.
- `ProductCatalogService.java:75-79`: si el producto no existe, lo auto-registra como activo con unidad por defecto `LB`/`UNIDAD`/`KG` y sin precio en `product_price`; la cotización queda incompleta y el total puede quedar nulo.
- `SaleController.java:149-150`: `POST /api/sales/orders/{id}/validate` llama a `autoApproveWholesale(orderId, false, null, user)` para cualquier pedido que no esté `APPROVED`, sin comprobar estado, cliente ni revisión.
- `SaleService.java:346`: `reprintByOrderId` también llama a `autoApproveWholesale(orderId, false, null, user)` si no existe venta. Un operador puede “reimprimir” un pedido dudoso y convertirlo en venta.
- `PrintService.java:74` y `PrinterConfigService.java:80`: la impresión no consulta el flag `enabled` de la configuración; ver P1-02.

**Escenarios concretos**

1. Un cliente minorista pide "2 tomates". El prompt puede convertir eso en `1 LB` (ver P1-05 y sección 4). El backend no pregunta, autoaprueba y encola impresión.
2. Un cliente pide un producto que no existe. Se auto-registra en catálogo sin precio; la venta se crea con total nulo o incompleto y se imprime.
3. Llega una foto ilegible. El prompt asigna cantidad `1.0` y `ambiguo=true`; si el producto se resolvió, `hasTrulyDoubtfulLines` no lo detecta y se autoaprueba.
4. Un pedido queda en `REVIEW` con una línea dudosa y `requires_verification=true`. El operador pulsa “validar” o “reimprimir”; se crea la venta y el `print_job` aunque la duda no se haya resuelto.
5. Un pedido vacío o sin líneas puede terminar con venta y trabajo de impresión; el ticket sale sin detalle.
6. Un pedido `CANCELLED` puede ser aprobado por `validateOrder` o `reprintByOrderId`, porque `autoApproveWholesale` no valida el estado.

**Corrección**

- Separar dos conceptos que hoy son uno: “cliente mayorista” y “autoaprobar/autoimprimir”. El flag global no debe reclasificar clientes.
- Antes de crear venta: exigir `status == REVIEW`, `customer_type == WHOLESALE` (si la regla es solo mayorista), `io.items()` no vacío, `quote.complete()`, `review == GREEN` o confirmación explícita, y `printerConfig.enabled()`.
- Si algo falla, dejar el pedido en `REVIEW`, no crear `sale` ni `print_job`, y devolver un conflicto explicable.
- Quitar los fallbacks silenciosos `BigDecimal.ONE`; una cantidad faltante debe bloquear o pedir confirmación.
- No auto-crear productos desde texto libre; proponerlos para aprobación y guardar precio/unidad nulos o “por definir”.
- `validateOrder` solo debe limpiar la marca de duda, no aprobar; `reprintByOrderId` debe exigir una venta existente y un pedido aprobado/impreso.
- Añadir pruebas: minorista, pedido vacío, producto desconocido, cantidad nula, foto ambigua, pedido cancelado, impresora deshabilitada y doble clic.

---

### P0-04 — XSS almacenado en la vista del ticket y cadena hacia el reset destructivo

**Evidencia**

- `index.html:586` define `buildTalonarioView(t)`.
- `index.html:680-687` interpola sin escapar `customerName`, `customerDoc`, `address`, `phone` y `t.notes` dentro de HTML.
- `index.html:751` hace `talonarioContainer.innerHTML = buildTalonarioView(t);`.
- `SecurityConfig.java:52-54` permite `script-src 'self' 'unsafe-inline'`; un `<img onerror=...>` o `<script>` inline puede ejecutarse.
- `SecurityConfig.java:43` usa `CookieCsrfTokenRepository.withHttpOnlyFalse()`: el token CSRF es legible por JavaScript, por lo que el XSS puede enviar peticiones autenticadas con el header correcto.
- `DevSecurityConfig.java:24` desactiva CSRF para `/api/dev/**`; el XSS puede llamar directamente a `POST /api/dev/database/reset` con la cookie de sesión, sin token.
- Los datos del cliente y del pedido vienen de WhatsApp (nombre de perfil, texto, dirección, notas) y de la extracción de IA; no se sanean antes de la vista previa.

**Impacto**

Cadena: cliente controla nombre/notas → operador abre la vista previa del ticket → se ejecuta JavaScript en el origen de la aplicación → lee el token CSRF y llama endpoints autenticados → puede borrar la base de desarrollo o aprobar/borrar ventas. No se necesita que el operador haga clic en un enlace: basta con abrir el pedido. El CSP actual no mitiga el evento inline.

**Corrección**

- Construir el HTML con `textContent`/`createElement`, o escapar todos los valores con una función de escape HTML antes de `innerHTML`.
- Sanear también en el servidor: nombre, dirección, notas, teléfono y nombres de producto deben tener límites y caracteres de control eliminados.
- Eliminar `'unsafe-inline'` del CSP y usar nonce/hash para los scripts legítimos.
- Mantener CSRF en todas las rutas que mutan, incluidas las de `dev`; si `/api/dev` se conserva, exigir `ADMIN` y red local.
- Añadir una prueba con un nombre/nota que contenga `<img src=x onerror=...>` y verificar que se muestra como texto.

---

### P0-05 — Piloto público con perfil `dev`, endpoints de desarrollo y cookie no segura

**Evidencia**

- El contenedor `fruvercopilot-backend` está healthy y `http://localhost:8085/login.html` responde `200`.
- El túnel ngrok activo publica `https://jurist-resubmit-ended.ngrok-free.dev`; `GET /login.html` responde `200`.
- La URL anterior `https://6cfc-190-66-78-253.ngrok-free.app/login.html` responde `404` con `Ngrok-Error-Code: ERR_NGROK_3200` (túnel offline); la URL fija es la que está viva.
- Variables efectivas del contenedor: `SPRING_PROFILES_ACTIVE=dev`, `DEV_ENDPOINTS=true`, `WHATSAPP_ENABLED=true`, `PRINTER_ENABLED=true`, `AUTO_CONFIRM_CLEAR_ORDERS=true`, `AI_AUTO_REPLY=true`, `COOKIE_SECURE=false`.
- `AUTO_PRINT_WHOLESALE` no está definido; aplica el default `true` de `application.yml:82`.
- `ProductionGuard` solo actúa con perfil `prod`; con `dev` no bloquea `DEV_ENDPOINTS`, seeds ni configuraciones peligrosas.
- La suscripción del webhook de Meta a la URL actual no se verificó en esta ronda.

**Impacto**

No es un entorno de prueba aislado: es una aplicación de negocio, con WhatsApp y una impresora configurada, expuesta a Internet con endpoints de desarrollo y una cookie de sesión que puede viajar sin `Secure`. Cualquier operador autenticado puede alcanzar `/api/dev/**`; el XSS de P0-04 puede hacerlo sin conocer credenciales. El secreto de P0-01 permite fabricar webhooks.

**Corrección**

- Sacar el piloto de `dev`: usar `prod`, `DEV_ENDPOINTS=false`, `COOKIE_SECURE=true`, seeds apagados y `ProductionGuard` activo.
- Si se necesita el simulador, levantarlo en una instancia local separada, sin túnel público y con credenciales de prueba.
- Restringir el túnel a una allowlist/VPN o cerrarlo mientras se corrigen los P0; actualizar la URL de Meta solo cuando la instancia sea la definitiva.
- Verificar la suscripción del webhook y el `phone_number_id`; no asumir que Meta está apuntando a la URL correcta.
- Rotar credenciales y revisar sesiones activas después del cambio.

---

## 2. Hallazgos P1

### P1-01 — “DONE” y “prueba exitosa” no significan que la impresión física ocurrió

**Evidencia**

- `PrintService.java:78-88`: tras `IppClient.printRaster(...)` o `printer.print(...)`, actualiza `print_job.status='DONE'` inmediatamente.
- `IppClient.java:75-88`: solo valida que el HTTP sea `200` y que el status IPP sea `<= 0x00FF`; si el body viene vacío o con menos de 4 bytes, lo acepta como éxito. No consulta `Get-Job-Attributes` ni confirma que la impresora terminó.
- `PrinterConfigService.java:179-188`: el test IPP envía una página y retorna en cuanto la impresora acepta el trabajo.
- `SaleController.java:135`: el endpoint devuelve `"Impresión de prueba enviada exitosamente"`.
- `TalonarioRasterService.java:45-55`: la página de prueba ya contiene el texto “CONEXIÓN IPP EXITOSA” antes de que la impresora confirme nada.

**Impacto**

La interfaz puede mostrar “VENDIDA”/“IMPRESO” sin papel, con papel trabado, sin tóner o con un trabajo en cola. El operador cree que el cliente tiene su comanda y deja de reintentar. El test de impresora da falsa confianza.

**Corrección**

- Distinguir estados `QUEUED`, `ACCEPTED`, `PRINTING`, `COMPLETED`, `FAILED`; solo `COMPLETED` debe equivaler a “impreso”.
- Tras `Print-Job`, guardar `job-id`/`job-uri` y consultar `Get-Job-Attributes` con timeout y backoff hasta `completed` o error.
- Si el body IPP es vacío o corto, tratarlo como respuesta inválida, no como éxito.
- Cambiar los textos a “trabajo aceptado por la impresora” y “verificar la página física”; no afirmar “impreso” desde el ACK.
- Añadir una prueba con respuesta IPP vacía y otra con job en error.

---

### P1-02 — El checkbox de impresora no controla el worker; además puede duplicar trabajos

**Evidencia**

- `PrinterConfigService.java:80` lee `enabled` de la base y `PrinterConfigService.java:97` lo devuelve, pero `PrintService` nunca consulta `printerConfig.get().enabled()`.
- `application.yml:70` usa `PRINTER_ENABLED` solo para elegir el bean al arrancar; cambiarlo en caliente no detiene el worker.
- `PrintService.java:57-74`: el tick reclama el siguiente trabajo sin comprobar `enabled`; si hay un `Job`, imprime.
- `PrintService.java:125-132`: `reprintAllPending` resetea a `PENDING` todos los `FAILED` y `PRINTING`; puede reencolar un trabajo que otro worker está imprimiendo.
- `PrintService.java:146-160`: los jobs con más de 2 minutos en `PRINTING` se vuelven a reclamar; una impresión lenta puede duplicarse.
- `PrintService.java:74` usa el `target_printer` guardado al crear la venta; no reenruta según la hora actual. Un pedido creado a las 18:59 para OFICINA puede seguir intentando OFICINA a las 19:01, con la oficina cerrada.

**Impacto**

El operador apaga la impresora en la UI y la aplicación sigue imprimiendo; o al revés, cree que está apagada y hay trabajos duplicados. Los cambios de horario no aplican a jobs ya creados.

**Corrección**

- Gate del worker: si `!printerConfig.get().enabled()`, no reclamar jobs; dejar `PENDING` y auditar la pausa.
- Hacer que el checkbox actualice el estado efectivo del worker, no solo la fila de base de datos.
- Usar un lease con token/worker-id y un timeout mayor que el peor caso; no reclamar un `PRINTING` sin verificar que el worker anterior murió.
- Al reclamar, decidir el destino con `currentTargetPrinter` si el job aún no empezó; guardar el destino elegido en el momento de imprimir.
- `reprintAllPending` solo debe tocar `FAILED` confirmados, no `PRINTING` activos.

---

### P1-03 — Mensajes de WhatsApp sin opt-in, plantilla ni ventana de 24 horas; teléfono completo en logs

**Evidencia**

- `PrintService.java:93-117`: después de imprimir, si no es reimpresión, envía `confirmMsg = "🙏👍"` por WhatsApp y registra el teléfono completo en el log.
- `SaleService.java:142-145`: si no es “wholesale”, intenta enviar la factura por WhatsApp; con `autoPrintWholesale=true`, la mayoría de pedidos no la recibe.
- `WhatsAppCloudClient.java:38-47`: `sendText` envía texto libre; no usa plantillas ni verifica la ventana de 24 h.
- `WhatsAppCloudClient.java:50-60`: `sendImage` envía una URL; tampoco usa plantilla.
- No hay opt-in, opt-out, registro de consentimiento ni fallback a plantilla cuando Meta rechaza el mensaje.
- `PrintService.java:113`: incluso con impresora simulada (`PRINTER_ENABLED=false`), el flujo puede intentar enviar el mensaje.

**Impacto**

Meta puede rechazar los mensajes fuera de la ventana de 24 h; el cliente no recibe confirmación y el sistema no lo sabe. Enviar un emoji sin contexto puede ser spam. El teléfono completo en logs es PII y complica el cumplimiento.

**Corrección**

- Usar plantillas aprobadas para confirmación de impresión/factura, con parámetros y fallback.
- Registrar opt-in/opt-out y respetar la ventana; si no hay ventana, usar plantilla o no enviar.
- No enviar confirmación si la impresora es simulada o el trabajo no está `COMPLETED`.
- Enmascarar teléfonos en logs (`+57******1234`) y no registrar contenido completo de conversaciones.
- Añadir pruebas de rechazo 4xx de Meta y de ventana expirada.

---

### P1-04 — Inyección HTML en el rasterizador y procesos externos sin timeout

**Evidencia**

- `TalonarioRasterService.java:112-160`: `buildHtml` concatena `customer`, `phone`, `address`, `notes`, `name`, `unit` y cantidades sin escape.
- `TalonarioRasterService.java:62-96`: escribe los temporales en `/tmp/talonario_*`, lanza `wkhtmltopdf` y `gs`, y hace `waitFor()` sin timeout.
- Si `wkhtmltopdf` o Ghostscript se cuelgan, el worker de impresión queda bloqueado; no hay `destroyForcibly` ni cleanup de procesos en el `finally`.
- El HTML generado se pasa a `wkhtmltopdf`, que puede ejecutar JavaScript y cargar recursos externos según su configuración. Un nombre/nota malicioso puede alterar el ticket o provocar SSRF.
- Las rutas fijas en `/tmp` no usan un directorio temporal por ejecución; en Windows/Docker pueden quedar archivos o colisionar.

**Impacto**

El ticket puede imprimir contenido falso, incluir recursos externos o colgar la impresión. La inyección es distinta del XSS del navegador (P0-04), pero comparte la misma entrada no confiable.

**Corrección**

- Escapar todo el contenido con una función HTML y desactivar JavaScript/recursos externos en `wkhtmltopdf` (`--disable-javascript`, `--disable-local-file-access`, sin `--enable-...`).
- Usar `Files.createTempDirectory` y borrar el directorio completo en `finally`.
- `waitFor(20, TimeUnit.SECONDS)` y `destroyForcibly()` si expira; capturar stdout/stderr con límite.
- Validar que las rutas de salida estén dentro del directorio temporal.
- Añadir una prueba con `<script>`/`<img onerror>` en nombre y notas.

---

### P1-05 — Catálogo y cantidades inventados por el backend y por el prompt

**Evidencia**

- `ProductCatalogService.java:75-79`: auto-registra un producto activo con unidad `LB` (o `UNIDAD`) cuando no lo encuentra; no exige precio ni aprobación.
- `ProductCatalogService.java` crea productos para nombres de 3 a 80 caracteres sin dígitos; un nombre con HTML sin dígitos puede entrar al catálogo.
- `SaleService.java:113` y `190`: cantidad faltante → `BigDecimal.ONE`.
- `OrderWriter` también usa `BigDecimal.ONE` cuando no hay cantidad en la reconstrucción de ítems.
- `SystemPrompts.java:35-39`: instruye que para “2 tomates” use cantidad 1 (o estimada), unidad `LB` y `ambiguo=false`, aunque la línea 75-78 dice que sin unidad la cantidad debe ser nula.
- `SystemPrompts.java:55`: si el producto es por LB/KG y no hay cantidad exacta, asigna 1 y no marca ambigüedad.
- `SystemPrompts.java:128-131`: si la cantidad de la imagen es ilegible, asigna `1.0` y `ambiguo=true`; si el producto se resuelve, el filtro de autoaprobación puede no detectar la ambigüedad.

**Impacto**

El sistema puede facturar 1 libra donde el cliente pidió 2 tomates o no dijo cantidad; el catálogo se llena de productos basura sin precio; los reportes y el inventario quedan contaminados. Es una "alucinación" que se convierte en una venta y una impresión reales.

**Corrección**

- Quitar los defaults `1`/`LB`; si falta cantidad o unidad, dejar nulo y marcar `requires_verification`.
- No auto-crear productos; crear una propuesta pendiente y pedir aprobación.
- Corregir el prompt para que “2 tomates” sea cantidad 2, unidad `UNIDAD` (o nula si no se puede inferir), y para que una cantidad ilegible no se invente.
- Hacer que el filtro de autoaprobación use `io.review()` y `ambiguo` completo, no solo dos condiciones.
- Añadir pruebas de “2 tomates”, “papa” sin cantidad, foto ilegible y producto desconocido.

---

### P1-06 — El webhook responde `200` aunque el procesamiento falle

**Evidencia**

- `WhatsAppWebhookController.java:75`: itera `messages` y llama `handle(m, names)`.
- `WhatsAppWebhookController.java:82`: devuelve `ResponseEntity.ok().build()` al final, sin comprobar excepciones del procesamiento.
- `WhatsAppWebhookController.java:85-110`: `handle` procesa texto/audio/imagen; los `return` tempranos no generan error.
- No se procesan `statuses` (delivered/read) y no se valida `phone_number_id`.
- La firma HMAC se valida, pero una vez válida el controller no distingue un mensaje duplicado de uno fallido.

**Impacto**

Meta cree que el mensaje fue entregado y no reintenta; un fallo transitorio de BD/IA pierde el pedido. Tampoco hay trazabilidad de estados de entrega. Un `phone_number_id` ajeno puede entrar si el secreto se filtra.

**Corrección**

- Validar `phone_number_id` contra el configurado.
- Procesar `statuses` o ignorarlos explícitamente y documentarlo.
- Responder `5xx` solo ante fallos transitorios que Meta deba reintentar; usar idempotencia por `message.id` para duplicados.
- Encolar el mensaje y responder rápido; no hacer trabajo pesado dentro del request.
- Añadir una prueba de handler que lanza excepción y verificar el status.

---

### P1-07 — Desactivación de productos por la frase “no hay” y mensajes automáticos peligrosos

**Evidencia**

- `ConversationService.java:97-107`: si un mensaje saliente contiene `"no manejamos"`, `"no se maneja"`, `"no hay"` o `"agotado"` y el nombre de un producto del pedido, ejecuta `UPDATE product SET active = FALSE`.
- La comparación es un `contains` de substring; “no hay problema con la papa” contiene “no hay” y “papa”, y desactiva papa.
- El mensaje puede ser automático o escrito por el operador; no hay confirmación ni auditoría del cambio de catálogo.

**Impacto**

Un mensaje normal puede dejar un producto fuera de catálogo globalmente; los clientes posteriores no lo ven y se pierden ventas. La desactivación no se revisa ni se revierte.

**Corrección**

- No inferir acciones de catálogo desde texto libre; usar una acción explícita del operador (“marcar agotado”).
- Si se mantiene la heurística, exigir confirmación, revisar negaciones y registrar auditoría.
- Añadir una prueba con “no hay problema” y verificar que no desactiva.

---

### P1-08 — El “túnel 24/7” no es 24/7 y la documentación promete 6 segundos

**Evidencia**

- `scripts/tunnel_keeper.js:12-15`: dominio fijo y ping cada 10 minutos.
- `scripts/tunnel_keeper.js:43-45`: si ya hay cualquier `ngrok.exe`, no lo inicia; no verifica que sea el túnel correcto.
- `scripts/tunnel_keeper.js:64-70`: si el health falla, `taskkill /F /IM ngrok.exe` mata **todos** los ngrok y arranca uno nuevo.
- `scripts/tunnel_keeper.js:72-79`: espera 5 segundos y reintenta; si falla, espera al siguiente ciclo de 10 minutos.
- No hay backoff, no se renueva la cuota y el `403` de cuota se interpreta como caída.
- `DOCUMENTACION.md:399-402` afirma que reinicia “en menos de 6 segundos” y que mantiene el túnel “sin interrupción”; el ciclo de detección es de 10 minutos y el proceso puede quedar caído hasta el siguiente ping.

**Impacto**

El webhook de Meta puede estar caído hasta 10 minutos sin que el keeper lo detecte; si hay otro ngrok, puede matarlo. La documentación genera una falsa expectativa operativa. La URL actual responde, pero el mecanismo no garantiza continuidad.

**Corrección**

- Usar un servicio real (systemd/Windows Service/PMD2) o un proveedor con URL estable y cuota.
- Verificar que el proceso sea el túnel correcto y no matar procesos ajenos.
- Implementar backoff exponencial, límite de reinicios y alerta cuando la cuota esté agotada.
- Corregir la documentación: no prometer 6 segundos si el ping es de 10 minutos.

---

### P1-09 — Suite de pruebas roja y documentación que declara todo verificado

**Evidencia y resultados**

- Maven: **91 tests, 0 fallos de aserción, 12 errores, 0 omitidos, BUILD FAILURE, 3:51**, excluyendo `ExtractionEvalTest`.
- Los 12 errores están en:
  - `MultimodalBatchBurstIntegrationTest` (1)
  - `SaleFlowIntegrationTest` (8)
  - `WholesaleFlowIntegrationTest` (3)
- Son `ConditionTimeoutException` de Awaitility a los 10 s, no fallos de aserción; el reporte previo ya señalaba que el debounce efectivo de 20 s excede la espera de 10 s (`AUDITORIA_RESUMEN.md:27`).
- Frontend: **91 OK, 1 FAIL, exit 1**. Falla `Pedido 10009 tiene botón REINTENTAR IMPRESIÓN`; la UI actual tiene el botón con otra etiqueta (`REINTENTAR / IMPRIMIR`), así que es una expectativa desactualizada, no una pérdida de función.
- `ExtractionEvalTest` se excluyó a propósito: carga `.env` en un bloque estático y se auto-habilita si hay `GEMINI_API_KEY` u `OPENAI_API_KEY`; ejecutarlo sin control puede hacer llamadas reales. En esta ronda no hubo llamadas live a Gemini.
- `AUDITORIA.md:3-4` afirma “TODO CORREGIDO”, 39 tests OK; `AUDITORIA_RESUMEN.md:26-27` afirma 92 comprobaciones frontend y 94 tests; `RESUMEN_PRODUCCION.md:28-29` afirma Docker con tests y 39 OK. Ninguna coincide con el estado actual.

**Impacto**

No hay una señal verde confiable para desplegar. La suite tarda y falla por tiempos, no por producto, lo que oculta regresiones reales. La documentación induce a creer que los P0 están cerrados.

**Corrección**

- Alinear `debounce-seconds` de los tests con la espera (0 o >20 s) y no depender de tiempos reales de scheduler; usar reloj/inyección o `Awaitility` con margen.
- Arreglar la expectativa del botón o la etiqueta, y volver a correr.
- Excluir explícitamente `ExtractionEvalTest` en el comando por defecto o aislarlo en un perfil `live-eval` que no lea `.env` automáticamente.
- Actualizar los informes con resultados reales y fecha/commit; no declarar “TODO CORREGIDO” sin evidencia.
- Añadir pruebas de los P0: autoaprobación minorista, pedido vacío, XSS, impresora deshabilitada, IPP sin body, reset no autorizado.

---

## 3. Hallazgos P2 / higiene

### P2-01 — PII en logs

- `ConversationService.java:48` registra el texto completo de un mensaje duplicado; puede contener nombre, dirección o teléfono.
- `PrintService.java:117` registra el teléfono completo al confirmar impresión.
- `LocalMessagingGateway.java:19` registra solo longitud, bien; pero el resto no está uniformemente enmascarado.
- **Corrección:** enmascarar teléfonos, no loguear contenido de conversaciones y usar un logger con filtros de PII.

### P2-02 — Scripts de calibración y de impresión fuera del build

- `scripts/print_talonario_ipp.js` tiene la orden `6` y una lista de 30 ítems hardcodeada; no está integrado al build ni al flujo de la aplicación.
- `scripts/tests_calibracion/` contiene 13 scripts (`print_30_per_page.js`, `test_32.js`, `test_34.js`, `test_35.js`, `test_chunked.js`, `test_color_fix.js`, `test_expand.js`, `test_footer_height.js`, `test_grandes.js`, `test_layout.js`, `test_p1.js`, `test_p2.js`, `test_split.js`) con lógica vieja de 30/32/34/35 ítems.
- No hay `package.json` raíz y el módulo `ipp` no está declarado en un manifiesto reproducible; ejecutar estos scripts puede fallar o imprimir en una impresora real por accidente.
- **Corrección:** mover a `tools/` fuera del camino de producción, documentar que requieren una impresora de prueba, eliminar los duplicados y no incluirlos en la imagen.

### P2-03 — Documentación contradictoria o desactualizada

- `DOCUMENTACION.md:77,90,112,209,219,243` sigue describiendo TCP 9100/impresora térmica; `DOCUMENTACION.md:378-402` añade IPP 631/A5. La tabla de `PRINTER_PORT` dice 9100, pero el runtime usa 631; `PRINTER_HOST` de ejemplo es `192.168.1.200`, el runtime `.5`.
- `DOCUMENTACION.md:401` promete reinicio en menos de 6 segundos, falso (ver P1-08).
- `README.md:13,123,144` sigue diciendo ESC/POS 9100; no menciona IPP.
- `README.md:48-49` sugiere que `mvn test` no ejecuta la evaluación live y que esta se lanza con `OPENAI_API_KEY=...`, pero `ExtractionEvalTest` lee `.env` y se auto-habilita; el comando “normal” puede hacer llamadas reales.
- `AUDITORIA.md:10` habla de migraciones V1..V7; el repositorio ya tiene V13/V16/V17.
- **Corrección:** una sola fuente de verdad para impresión (TCP vs IPP), valores reales del entorno, y una sección explícita de pruebas live separadas.

### P2-04 — Ruido de `.gitignore`

- `git status` muestra `.gitignore` modificado; el diff es ruido de CRLF, no un cambio funcional. No se tocó.
- **Corrección:** normalizar line endings y evitar commits de ruido.

### P2-05 — Supuestos de hardware no verificados

- El código y la documentación asumen Brother HL-L2405W, `cupsColorSpace=18` y `sgray_8` a 600 DPI. No se consulta la capacidad real de la impresora.
- Si el modelo o el firmware no aceptan ese raster, el trabajo puede fallar o salir invertido; el test IPP no lo detecta porque solo confirma aceptación.
- **Corrección:** consultar atributos IPP (`Get-Printer-Attributes`), probar en el modelo real y documentar el resultado; no fijar supuestos de hardware sin verificación.

---

## 4. Posibles alucinaciones y contradicciones

| Afirmación | Dónde | Estado real |
|---|---|---|
| “TODO CORREGIDO” y “39 tests OK” | `AUDITORIA.md:3-4` | Falso: 91 tests, 12 errores, BUILD FAILURE; los P0 siguen |
| “Docker corre tests en el build” | `RESUMEN_PRODUCCION.md:28` | Falso: el Dockerfile empaqueta con `-DskipTests` |
| “WhatsApp desactivado” | `RESUMEN_PRODUCCION.md:18` | Falso: runtime `WHATSAPP_ENABLED=true` |
| “Frontend sin `innerHTML` para datos del pedido” | `AUDITORIA_RESUMEN.md:65` | Falso: `index.html:751` usa `innerHTML` con datos sin escapar |
| “`PRINTER_ENABLED=false` en el entorno local” | `AUDITORIA_RESUMEN.md:30,83` | Falso: runtime `PRINTER_ENABLED=true` |
| “Reinicia el túnel en menos de 6 segundos” | `DOCUMENTACION.md:401` | Falso: el ping es cada 10 min; el reintento interno es 5 s solo después de detectar |
| “Impresión de prueba enviada exitosamente” / “CONEXIÓN IPP EXITOSA” | `SaleController.java:135`; `TalonarioRasterService.java:45-55` | Solo significa que la impresora aceptó el trabajo; no que imprimió |
| “2 tomates” → cantidad 1 LB, sin ambigüedad | `SystemPrompts.java:35-39` | Contradice las reglas 75-78 y produce una venta incorrecta si se autoaprueba |
| “Cantidad ilegible → 1.0 y ambigua” | `SystemPrompts.java:128-131` | El `1.0` puede pasar el filtro si el producto se resolvió; la ambigüedad no bloquea |
| Modelos `gemini-3.5-flash-lite`, `gemini-3.8-flash`, `gemini-3.5-flash-lite` | `application.yml:99`; `GeminiOrderExtractor` lista de fallback | No verificados contra la API en esta ronda; si no existen, cada extracción pierde tiempo/cuota |
| “Cero dependencias” para IPP | `DOCUMENTACION.md:384` | El contenedor depende de `wkhtmltopdf` y Ghostscript (`TalonarioRasterService.java:70-96`) |
| “35 ítems caben exactos en una hoja” | `DOCUMENTACION.md:388-398` | No se validó físicamente en esta ronda; el test IPP solo confirma aceptación |

**Conclusión:** las afirmaciones de “todo verificado” no son confiables. La IA y el backend comparten una regla de negocio peligrosa: convertir silenciosamente una cantidad faltante o ilegible en `1`, y luego autoaprobar. Eso debe tratarse como defecto de producto, no como un detalle del prompt.

---

## 5. Código muerto, duplicado o fuera de build

| Elemento | Estado | Riesgo |
|---|---|---|
| `scripts/print_talonario_ipp.js` | Orden 6 hardcodeada, 30 ítems, no integrado | Puede imprimir una comanda de prueba en una impresora real |
| `scripts/tests_calibracion/*.js` (13 archivos) | Lógica vieja de 30/32/34/35, sin manifiesto ni CI | Duplica el layout actual; mantenimiento y confusión |
| `POST /api/dev/demo/reset` (`DevController.java:51`) | Duplica `reset-test-data` | Segunda puerta destructiva innecesaria |
| `scripts/tunnel_keeper.js` + `iniciar_tunel_24_7.bat` | Se ejecuta manualmente, no como servicio | No es 24/7; frágil (P1-08) |
| `apps/` | No se encontró contenido de aplicación | No se usa; no es un hallazgo funcional |
| `AUDITORIA.md`, `RESUMEN_PRODUCCION.md`, `AUDITORIA_RESUMEN.md` | Informes históricos con afirmaciones falsas | No deben usarse como fuente de verdad |

---

## 6. Pruebas ejecutadas y estado operativo

### Backend

- Comando: Maven con `-Dtest='!ExtractionEvalTest'` y variables de IA vacías.
- Resultado: **91 tests, 0 fallos de aserción, 12 errores, 0 omitidos, BUILD FAILURE**, 3:51.
- Errores: timeouts de Awaitility de 10 s en `MultimodalBatchBurstIntegrationTest`, `SaleFlowIntegrationTest` y `WholesaleFlowIntegrationTest`. No hubo llamadas live a Gemini.
- `ExtractionEvalTest` excluido: su bloque estático carga `.env` y se auto-habilita si hay clave; no debe correr en una auditoría sin consentimiento.

### Frontend

- Comando: `node frontend-tests/operacion.test.js`.
- Resultado: **91 OK, 1 FAIL, exit 1**.
- Falla: expectativa de texto `REINTENTAR IMPRESIÓN`; la UI actual muestra `REINTENTAR / IMPRIMIR`. El botón existe, por lo que es deuda de prueba, no una regresión funcional.

### Runtime

- `docker ps`: `fruvercopilot-backend` healthy.
- `http://localhost:8085/login.html`: `200`.
- `https://jurist-resubmit-ended.ngrok-free.dev/login.html`: `200`.
- `https://6cfc-190-66-78-253.ngrok-free.app/login.html`: `404`, `Ngrok-Error-Code: ERR_NGROK_3200` (túnel offline).
- No se autenticó, no se envió WhatsApp, no se imprimió y no se llamó ningún endpoint que mutara datos.
- No se verificó la suscripción actual de Meta al webhook.

---

## 7. Plan mínimo recomendado

### Fase 0 — Contención inmediata (hoy)

1. Bajar el túnel público o restringirlo mientras se corrige; no dejar el piloto con `dev` y `DEV_ENDPOINTS=true`.
2. Rotar `WHATSAPP_APP_SECRET` y access token; invalidar los anteriores.
3. Confirmar que Meta apunta a una URL controlada o pausar la integración.
4. No ejecutar `reset` ni `reopen`/merge sobre datos reales.

### Fase 1 — P0 (antes de cualquier prueba con clientes)

1. Purgar el secreto del historial (requiere autorización explícita).
2. Eliminar/aislar `/api/dev/**`; exigir `ADMIN`, CSRF y red local; eliminar `demo/reset`.
3. Gate de autoaprobación: cliente, estado, revisión, ítems, cotización completa e impresora habilitada.
4. Quitar `BigDecimal.ONE` y la auto-creación de productos; corregir el prompt.
5. Escapar la vista del ticket, quitar `unsafe-inline` y sanear el HTML del rasterizador.
6. Bloquear `reopen`/merge si la venta está impresa/despachada; no truncar `audit_log`.

### Fase 2 — P1

1. Estado real de impresión (`ACCEPTED` vs `COMPLETED`, `Get-Job-Attributes`).
2. Gate del checkbox de impresora, leases y no duplicar `PRINTING`.
3. Plantillas/opt-in/ventana de 24 h; enmascarar PII.
4. Webhook: `phone_number_id`, `statuses`, idempotencia y reintentos.
5. Corregir la desactivación por “no hay”.
6. Timeouts de `wkhtmltopdf`/Ghostscript y temporales seguros.
7. Túnel como servicio o proveedor estable; corregir la documentación.

### Fase 3 — Calidad

1. Suite Maven verde (ajustar debounce/esperas; aislar `ExtractionEvalTest`).
2. Frontend verde (etiqueta del botón).
3. Pruebas nuevas de los P0 y de impresión real.
4. Actualizar los informes y eliminar las afirmaciones “TODO CORREGIDO”.

---

## 8. Autorizaciones pendientes

No se ejecutó ninguna corrección. Para avanzar se necesita autorización explícita para:

- Modificar código y configuración (P0/P1).
- Rotar credenciales y cambiar la configuración de Meta.
- Reescribir el historial Git (operación destructiva; requiere backup y coordinación de force-push).
- Hacer pruebas live de WhatsApp, impresora física, backup/restore y concurrencia.
- Cambiar el perfil de despliegue y la URL pública.

Mientras no exista esa autorización, este informe debe tratarse como diagnóstico y el piloto no debe abrirse a clientes.

---

## Anexo A — Evidencia principal por archivo

| Archivo | Líneas relevantes | Hallazgo |
|---|---|---|
| `AUDITORIA.md` | 3-4, 47 | Secreto vigente y afirmaciones falsas |
| `DevController.java` | 42-44, 51 | Reset total y demo reset |
| `DevSecurityConfig.java` | 23-26 | `/api/dev/**` sin CSRF y solo autenticación |
| `SaleController.java` | 135, 149-150, 377-380 | Test de impresora, validate y reset |
| `OrderIntakeService.java` | 42, 68-70 | Modo global y borrado de venta |
| `OrderProcessingService.java` | 272-287, 304-338 | Autoaprobación inmediata/diferida |
| `SaleService.java` | 82-96, 113, 153-210, 346 | Aprobación sin filtros, cantidad 1, reprint |
| `ProductCatalogService.java` | 75-79 | Auto-registro de producto |
| `OrderEditService.java` | 303-305, 367-379 | Borrado de venta y reopen GREEN |
| `PrintService.java` | 78-88, 93-117, 125-160 | DONE falso, WhatsApp, duplicados |
| `IppClient.java` | 75-88 | ACK IPP insuficiente |
| `TalonarioRasterService.java` | 62-96, 112-160 | `/tmp`, timeouts, inyección HTML |
| `index.html` | 586, 680-687, 751 | XSS almacenado |
| `SecurityConfig.java` | 43, 52-54 | Token CSRF legible y `unsafe-inline` |
| `ConversationService.java` | 97-107 | Desactivación por “no hay” |
| `WhatsAppWebhookController.java` | 75, 82, 85-110 | 200 ante fallos, sin statuses |
| `scripts/tunnel_keeper.js` | 12-15, 43-79 | Túnel no 24/7 |
| `DOCUMENTACION.md` | 77-112, 209-243, 378-402, 462-469 | Contradicciones de impresión/túnel/pruebas |
| `README.md` | 13, 48-49, 123, 144 | Documentación desactualizada |

---

**Fin del informe.**  
No se reproduce ningún secreto, token, teléfono de cliente ni contenido real de conversación.
