# Matriz de casos de prueba — FruverCopilot

**Fecha:** 2026-10-25  
**Commit:** `dc5d842` (rama `mayorista`)  
**Alcance:** casos ejecutados, fallidos, verificados estáticamente, bloqueados y faltantes. No es un conteo de cobertura ni una certificación.  
**Regla:** un caso `STATIC` no equivale a una prueba aprobada; solo indica que el código/configuración se revisó. Un caso `GAP` no tiene prueba ni implementación satisfactoria.

## Leyenda

| Estado | Significado |
|---|---|
| `PASS-E` | Ejecutado y pasó en esta auditoría. |
| `FAIL-E` | Ejecutado y falló (timeout, error o aserción). |
| `STATIC` | Verificado por lectura de código/configuración, sin ejecución. |
| `BLOCKED` | No ejecutable por falta de hardware, credenciales, número, entorno o autorización. |
| `GAP` | No hay prueba y/o la implementación no cubre el caso; riesgo abierto. |
| `VERIFIED` | Dato externo verificado en fuente oficial. |
| `CONFIRMED` | Hallazgo confirmado; ver informe de seguridad. |

**Resumen de ejecución:** 92 chequeos frontend OK; 94 tests backend, 82 sin error, 12 errores, 0 fallos de aserción, 0 omitidos; `BUILD FAILURE`. Los 12 errores son timeouts por debounce, no aserciones de negocio. Ver [AUDITORIA_PRUEBAS.md](AUDITORIA_PRUEBAS.md).

## A. Webhook y recepción

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| W-01 | P1 | GET con `hub.mode=subscribe` y verify token correcto → 200 + challenge. | PASS-E | `ControllersUnitTest.whatsAppWebhookControllerVerifyAndReceive`. |
| W-02 | P1 | GET con token incorrecto → 403. | PASS-E | Misma prueba. |
| W-03 | P0 | POST con HMAC-SHA256 válido → 200 y mensaje persistido. | GAP | No hay prueba de integración con firma real; el unit test usa secreto `ignore` y firma nula. |
| W-04 | P0 | POST con firma inválida/ausente → 403. | STATIC | `WhatsAppWebhookController.java:64-67, 112-126`; sin prueba. |
| W-05 | P1 | Body > 512.000 bytes → 413. | STATIC | `:59-62`; sin prueba. |
| W-06 | P0 | Mismo `external_message_id` dos veces → un solo pedido. | FAIL-E | `duplicateExternalMessageCreatesOneOrder` agotó 10 s; la idempotencia existe pero no quedó validada. |
| W-07 | P1 | Texto vacío → no crear pedido. | STATIC | `:93-95`. |
| W-08 | P1 | Audio → descargar, transcribir y asociar al pedido. | FAIL-E | `MultimodalBatchBurstIntegrationTest` timeout. |
| W-09 | P1 | Imagen → descargar, analizar y asociar. | FAIL-E | `imageOrderAutoApprovesAndEnqueuesPrintJob` timeout. |
| W-10 | P1 | Ráfaga audio+foto+texto+foto → un solo lote. | FAIL-E | `rafagaCompleta...` timeout. |
| W-11 | P1 | Tipo no soportado (reacción/sticker) → ignorar, no crear pedido. | GAP | El `default` crea `"[Mensaje de tipo ... no soportado]"` (`:108`). |
| W-12 | P1 | `statuses` de Meta → persistir entrega/lectura/fallo y pricing. | GAP | No se procesan. |
| W-13 | P1 | `metadata.phone_number_id` distinto → rechazar/ignorar y alertar. | GAP | No se valida. |
| W-14 | P0 | BD caída durante la ingesta → 5xx para que Meta reintente. | GAP | `:78-82` captura todo y responde 200; riesgo de pérdida. |
| W-15 | P1 | JSON malformado que pasó HMAC → 400/alerta, sin pedido. | GAP | Se captura y responde 200. |
| W-16 | P0 | Secreto `ignore/bypass/none` en prod → arranque bloqueado. | STATIC | `ProductionGuard` solo actúa con perfil `prod`; el `.env` local usa `dev`. |
| W-17 | P1 | `contacts[].wa_id` no coincide con `from` → no confiar en el nombre. | GAP | No se valida. |
| W-18 | P1 | Replay del mismo evento tras reinicio → no duplicar. | GAP | Depende de `external_message_id`; sin prueba de reinicio. |
| W-19 | P2 | Webhook responde rápido y procesa asíncrono. | GAP | Procesa en línea; una caída de IA/medios puede demorar la respuesta. |
| W-20 | P2 | Guardar payload crudo para auditoría/replay. | GAP | Solo se guarda el mensaje interpretado, no el evento crudo. |

## B. Ingesta, pedido y debounce

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| I-01 | P1 | Texto válido crea pedido en `REVIEW` con cliente y líneas. | FAIL-E | `SaleFlowIntegrationTest.newOrder` timeout por debounce. |
| I-02 | P1 | Mensajes consecutivos del mismo cliente → un pedido activo. | FAIL-E | `consecutiveMessages...` timeout. |
| I-03 | P1 | Respuesta del cliente se anexa al pedido en espera, no crea otro. | FAIL-E | `customerReplyAttaches...` timeout. |
| I-04 | P1 | Duplicado exacto → un pedido y un mensaje. | FAIL-E | `duplicateExternalMessage...` timeout. |
| I-05 | P0 | Adición a pedido aprobado no impreso → revisión sin borrar venta. | FAIL-E | `subsequentAdditions...` timeout; el código borra venta/print_job (`OrderIntakeService.java:63-71`). |
| I-06 | P1 | `debounce-seconds=0` → procesamiento inmediato. | FAIL-E | `Multimodal...` timeout; la fila V17=20 pisa el 0. |
| I-07 | P1 | Cambiar `CUSTOMER_DEBOUNCE_SECONDS` cambia el valor efectivo. | STATIC | Falla si existe `printer_setting.customer_debounce_seconds`; la base gana. |
| I-08 | P2 | Ráfaga supera `debounce-max-seconds` → procesa el lote. | GAP | Sin prueba. |
| I-09 | P2 | Reinicio en medio de una ráfaga → recuperación sin duplicar. | GAP | `recoverPendingExtractions` existe; sin prueba de reinicio. |
| I-10 | P2 | Dos instancias con temporizadores → no duplican procesamiento. | GAP | Temporizadores en memoria; sin prueba multi-instancia. |
| I-11 | P1 | Teléfono inválido/texto > 2.000 → recorte/rechazo. | STATIC | `MessageIngestService.java:70-77`; sin prueba dedicada. |
| I-12 | P1 | Pedido activo solo dentro de 12 h. | STATIC | `OrderIntakeService.java:43-59`; sin prueba de borde. |
| I-13 | P0 | Solo clientes mayoristas entran al modo mayorista. | GAP | `wholesaleMode` usa `autoPrintWholesale`, no `customer_type` (`:42`). |
| I-14 | P2 | SSE notifica cambios al tablero. | GAP | No hay prueba de integración SSE; el frontend usa un fake. |

## C. IA, extracción y catálogo

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| A-01 | P1 | Texto simple → ítems, cantidades y acción válidos. | PASS-E | `ExtractionEvalTest` (3 live) + unitarias; no cubre todos los textos. |
| A-02 | P1 | Producto ambiguo (“papa”) → preguntar, no elegir. | PASS-E | `ambiguousPotatoAsksTheCustomer`; el flujo automático luego ignora la duda (P0-03). |
| A-03 | P1 | Inyección de prompt → tratar como dato, no como orden. | PASS-E | `promptInjectionIsTreatedAsData`. |
| A-04 | P1 | “5 mil de tomate” → dinero no es cantidad. | PASS-E | `moneyIsNotQuantity`. |
| A-05 | P1 | Audio → transcripción fiel y asociada. | BLOCKED | Requiere audio/STT real; el test multimodal timeout. |
| A-06 | P1 | Foto de lista → productos/cantidades. | FAIL-E | `imageOrderAuto...` timeout. |
| A-07 | P1 | Lote multimodal → una sola extracción consolidada. | FAIL-E | `rafagaCompleta...` timeout. |
| A-08 | P1 | Gemini saturado → cascada a otro proveedor. | PASS-E | `AiProviderCascadeAndSaturationTest` (3). |
| A-09 | P1 | IA caída → pedido en error para digitación manual, sin venta. | PASS-E | `aiDownKeepsTheOrderInRedAndItCanBeTypedManually`. |
| A-10 | P0 | Producto inexistente → candidato en revisión, no producto activo. | GAP | `ProductCatalogService.java:64-90` lo crea activo; P1-08. |
| A-11 | P0 | Unidad desconocida → no inferir `LB`/`UNIDAD` sin validación. | GAP | Inferencia en `:76-87`; puede producir unidad incorrecta. |
| A-12 | P2 | Typos/sinónimos → normalización sin falsos positivos. | PASS-E | `ExtractionInterpreterTest`/catálogo; cobertura parcial. |
| A-13 | P0 | Precio inventado por IA → ignorado; precio del backend. | STATIC | `SaleService`/`QuoteService` recalculan; no se acepta precio del LLM. |
| A-14 | P1 | Dos líneas del mismo producto → rechazo del servidor. | FAIL-E | `sameProductOnTwoLines...` timeout. |
| A-15 | P1 | Cantidad negativa/cero/absurda → rechazo en servidor y DB. | PASS-E | `negativeZeroOrAbsurdValues...`. |
| A-16 | P0 | Salida vacía/ambigua → revisión, nunca venta. | GAP | `hasTrulyDoubtfulLines` existe, pero se llama igual a autoaprobar (P0-03). |
| A-17 | P2 | Medir tokens/costo por extracción. | GAP | `AppMetrics` solo mide tiempo/errores; no tokens. |
| A-18 | P1 | Test live aislado de la suite/CI. | GAP | `ExtractionEvalTest` carga `.env` y se autohabilita (P1-09). |

## D. Venta, aprobación y corrección

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| S-01 | P1 | Aprobación manual calcula precio y snapshot. | FAIL-E | `wholesaleCustomerGetsWholesalePrice...` timeout. |
| S-02 | P1 | Doble aprobación → una sola venta (idempotente). | FAIL-E | `approvingTwiceCreatesOnlyOneSale` timeout. |
| S-03 | P1 | Versión obsoleta → 409, sin venta. | FAIL-E | `staleVersionIsRejected` timeout. |
| S-04 | P1 | Precio manual se conserva en snapshot. | PASS-E | `manualPriceIsSnapshottedWithCatalogPrice`. |
| S-05 | P1 | Unidad distinta a la del producto → 422. | PASS-E | `unitDifferentFromProductUnitBlocksApproval`. |
| S-06 | P1 | Fallo de impresora conserva venta; reimprimir no crea otra venta. | PASS-E | `printerFailureKeepsSaleAndReprintDoesNotCreateAnotherSale`. |
| S-07 | P1 | Valores negativos/cero/absurdos → rechazo. | PASS-E | `negativeZeroOrAbsurdValuesAreRejectedByServerAndDatabase`. |
| S-08 | P1 | Dos líneas del mismo producto → rechazo. | FAIL-E | Timeout; no se validó. |
| S-09 | P2 | Zonas de entrega y formato de prompt. | PASS-E | `deliveryZonesConfigurationAndPromptFormat`. |
| S-10 | P2 | Despacho y factura por WhatsApp (mock). | PASS-E | `orderDispatchAndWhatsAppInvoiceFlow`. |
| S-11 | P0 | Anexar pedido → no borrar venta; consolidar con revisión. | FAIL-E | `mergeOrderIntoPendingDispatchOrder` timeout; el código borra (`OrderEditService.java:300-306`). |
| S-12 | P0 | Reabrir pedido impreso/despachado → 409, venta intacta. | PASS-E | `reopenOrderAllowsAddingMoreItems` pasa, pero **espera `saleId=null`**: codifica el defecto (`:365-370`). |
| S-13 | P0 | Autoaprobar pedido dudoso → revisión, sin venta. | PASS-E | `doubtfulOrderSetsVerificationFlag` pasa, pero **espera APPROVED** y solo verifica la bandera. |
| S-14 | P0 | Autoaprobar imagen dudosa → revisión, no venta. | FAIL-E | `imageOrderAutoApproves...` timeout. |
| S-15 | P0 | Pedido vacío → sin venta, sin print_job. | GAP | No hay prueba; el código llama a autoaprobar con `items.isEmpty()`. |
| S-16 | P0 | Línea sin cantidad → sin venta; no usar 1. | GAP | `BigDecimal.ONE` en `SaleService.java:188-190` y `OrderWriter.java:175-177`. |
| S-17 | P0 | Cliente minorista → no autoaprobar. | GAP | `autoApproveWholesale` no valida `customer_type`. |
| S-18 | P0 | Impresora deshabilitada → no crear print_job/venta “impresa”. | GAP | No valida `PRINTER_ENABLED`; el default de autoimpresión es `true`. |
| S-19 | P0 | `validateOrder` sobre pedido no aprobado → no crear venta. | GAP | `SaleController.java:144-146` llama a `autoApproveWholesale`. |
| S-20 | P0 | `reprintByOrderId` sin venta → no autoaprobar. | GAP | `SaleService.java:340-347` llama a `autoApproveWholesale`. |
| S-21 | P0 | Reset en prod → 404/403, datos intactos. | GAP | `SaleController.java:323-332` hace `TRUNCATE`; solo exige ADMIN. |
| S-22 | P0 | Cotización incompleta → no venta ni total nulo. | GAP | Autoaprobar permite `total=null`; DB lo permite desde V12. |
| S-23 | P0 | Pedido no `REVIEW` → autoaprobar rechaza. | GAP | No valida estado. |
| S-24 | P1 | Aprobación concurrente → una venta. | GAP | `FOR UPDATE` ayuda, pero no hay prueba de dos hilos/instancias. |
| S-25 | P0 | Venta aprobada inmutable. | GAP | Se borra en intake/merge/reopen; no hay estado `VOIDED`/reemplazo. |
| S-26 | P0 | Corrección de venta con motivo/actor/auditoría. | GAP | No implementado. |
| S-27 | P1 | Auditoría de venta append-only. | GAP | El reset borra `audit_log`; no hay protección de inmutabilidad. |
| S-28 | P1 | Precio mayorista distinto de retail y snapshot. | FAIL-E | Timeout; no validado. |
| S-29 | P1 | Total/precio nulo no permitido en venta normal. | STATIC | `sale.total` y `sale_item` permiten nulos desde V12. |
| S-30 | P1 | `sale.order_id` único. | STATIC | Restricción existe; no se probó concurrencia. |

## E. Impresión

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| P-01 | P1 | Aprobar crea un `print_job` y marca `PRINT_PENDING`. | PASS-E | Flujo de venta/reimpresión con mock. |
| P-02 | P1 | Worker envía bytes ESC/POS a la impresora. | STATIC | `PrintService.processOne`/`NetworkEscPosPrinter`; sin hardware. |
| P-03 | P0 | “Impreso” solo si hay evidencia física/ACK. | STATIC | Marca `PRINTED` tras `write+flush`; no hay ACK (`PrintService.java:68-73`). |
| P-04 | P1 | Fallo de red → reintentos y `PRINT_FAILED`. | PASS-E | `printerFailureKeepsSale...`; no hardware. |
| P-05 | P1 | Se agotan intentos → estado fallido visible. | PASS-E | Prueba de fallo de impresora. |
| P-06 | P1 | Reimprimir no crea otra venta. | PASS-E | `printerFailure...`. |
| P-07 | P2 | Reimpresión por pedido y masiva. | PASS-E | `reprintByOrderIdAndBulkReprintWork`. |
| P-08 | P0 | `reprintAllPending` no reencola un `PRINTING` activo. | STATIC | `PrintService.java:83-90` reencola `PRINTING`; riesgo de duplicado. |
| P-09 | P0 | Reinicio con `PRINTING` → recuperación sin doble envío ciego. | GAP | Reclamo a los 2 min; sin prueba. |
| P-10 | P0 | Borrar/merge/reopen durante `PRINTING` → bloqueo. | GAP | No hay lock; ver P0-02. |
| P-11 | P1 | Dos workers/instancias → `SKIP LOCKED`, sin duplicar. | STATIC | SQL correcto; sin prueba de concurrencia. |
| P-12 | P0 | Impresora deshabilitada → no simular impresión. | GAP | `PRINTER_ENABLED=false` no impide venta/print_job. |
| P-13 | P1 | Falta de papel, tapa, corte, cajón → detección. | BLOCKED | Requiere hardware/ESC/POS; no se probó. |
| P-14 | P1 | Exactly-once de impresión. | BLOCKED | No demostrable sin ACK; no prometerlo. |
| P-15 | P2 | UI distingue “enviado” de “impreso”. | PASS-E | Frontend muestra “IMPRESO”; semántica incorrecta, no costo. |

## F. WhatsApp saliente y costos

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| C-01 | P1 | Enviar texto dentro de ventana → 2xx y registrar. | STATIC | `WhatsAppCloudClient.sendText`; sin prueba real. |
| C-02 | P0 | Fuera de ventana 24 h → usar plantilla; no fallar. | BLOCKED | No hay plantillas ni número; el texto libre falla. |
| C-03 | P1 | Plantilla utility aprobada → enviar y registrar categoría. | GAP | No implementado. |
| C-04 | P1 | Webhook de `statuses` → delivered/read/failed. | GAP | No procesa. |
| C-05 | P1 | Guardar `wamid` de la respuesta. | GAP | `toBodilessEntity` descarta el cuerpo. |
| C-06 | P1 | `SENT` significa aceptado, no entregado. | STATIC | `SENT` tras HTTP 2xx; UI puede confundir. |
| C-07 | P0 | Opt-out/BAJA/STOP → suprimir envíos. | GAP | No implementado. |
| C-08 | P0 | Consentimiento registrado antes de marketing. | GAP | No implementado. |
| C-09 | P1 | Deduplicar envíos entre reinicios/instancias. | STATIC | Solo memoria, 8 s (`ConversationService.java:36-53`). |
| C-10 | P1 | Imagen falla → no duplicar con texto si ya se entregó. | STATIC | Fallback a texto (`WhatsAppCloudClient.java:65-68`); riesgo de doble cargo. |
| C-11 | P2 | Reenvío manual de factura → idempotente/confirmado. | STATIC | Endpoint sin cooldown; cada clic puede cobrar. |
| C-12 | P1 | Cupo 1.000 service y FEP medidos. | GAP | No hay ledger ni cálculo de ventana. |
| C-13 | P1 | Ledger de categoría/billable/costo. | GAP | No existe. |
| C-14 | P1 | Emojis no cambian el precio. | VERIFIED | Tarifa por mensaje/categoría; no por caracteres. |
| C-15 | P1 | Tarifas Colombia vigentes. | VERIFIED | USD 0,0125 marketing; USD 0,0008 service/utility/auth (2026-10-01). |
| C-16 | P1 | Conciliar factura Meta vs ledger. | BLOCKED | Sin acceso a Billing/tráfico. |
| C-17 | P2 | Calidad del número/bloqueos. | BLOCKED | Sin número real/ventana. |
| C-18 | P1 | Marketing solo con opt-in y retorno medido. | GAP | No implementado; costo 15,6× service. |

## G. Seguridad, autenticación y datos

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| SEC-01 | P0 | HMAC-SHA256 y comparación constante. | STATIC | Mecanismo correcto; el secreto está comprometido (P0-01). |
| SEC-02 | P0 | Ningún secreto en repositorio/historial. | CONFIRMED | `AUDITORIA.md` contiene el app secret actual; commit `d895c2e` en origin. |
| SEC-03 | P0 | Reset no disponible en prod. | CONFIRMED | `TRUNCATE` con solo rol ADMIN. |
| SEC-04 | P1 | 401/403 por rol en APIs. | GAP | El reporte viejo con 7 fallos no cuenta; no hay prueba actual. |
| SEC-05 | P1 | CSRF en `/api`, excluido en webhook/login. | STATIC | Configuración revisada; sin prueba de integración. |
| SEC-06 | P2 | Rate limit/bloqueo de login. | STATIC | Implementado; sin prueba de carga. |
| SEC-07 | P1 | Reinicio no revierte contraseña. | STATIC | `BootstrapUsers` hace upsert de `password_hash`; defecto. |
| SEC-08 | P1 | Política de clave ≥12. | STATIC | API permite 6; defecto. |
| SEC-09 | P1 | Cookie `Secure`/`HttpOnly`/`SameSite`. | STATIC | `prod` seguro; `.env` local `COOKIE_SECURE=false`. |
| SEC-10 | P1 | `ProductionGuard` bloquea config insegura. | STATIC | Solo con perfil `prod`; no cubre reset ni autoaprobación. |
| SEC-11 | P1 | Sin SQL injection en rutas revisadas. | STATIC | Consultas parametrizadas; no es un pentest. |
| SEC-12 | P2 | PII enmascarada en logs. | STATIC | Parcial; revisar logs/errores. |
| SEC-13 | P0 | Auditoría inmutable. | CONFIRMED | El reset borra `audit_log`. |
| SEC-14 | P1 | Escaneo de secretos en CI. | GAP | No hay evidencia. |
| SEC-15 | P1 | Dependencias sin CVEs críticas. | CONFIRMED | Boot 3.4.5 y otras versiones con avisos OSV. |
| SEC-16 | P1 | Backups cifrados/fuera de sitio. | GAP | Solo scripts; sin evidencia. |
| SEC-17 | P1 | Restore probado. | BLOCKED | Sin backup/entorno. |
| SEC-18 | P1 | Retención/PII/habeas data. | GAP | Sin política implementada. |
| SEC-19 | P1 | `DEV_ENDPOINTS` inexistente en prod. | STATIC | Guard solo con perfil prod; `.env` local activo. |
| SEC-20 | P1 | Actuator no expone detalles. | STATIC | Health público; metrics/info con rol; validar en prod. |

## H. Operación, frontend y dependencias

| ID | Pri | Caso y resultado esperado | Estado | Evidencia / nota |
|---|---|---|---|---|
| O-01 | P2 | Scripts de backup existen. | STATIC | `backups/backup.sh`/`.bat`; no hay copia reciente. |
| O-02 | P1 | Backup programado y monitoreado. | GAP | Sin evidencia. |
| O-03 | P1 | Restore completo en staging. | BLOCKED | No ejecutado. |
| O-04 | P1 | Monitoreo de cola, IA, impresión y WhatsApp. | GAP | Métricas parciales; sin alertas. |
| O-05 | P1 | Alertas de fallo/costo/calidad. | GAP | No implementadas. |
| O-06 | P2 | Prueba de carga y ráfagas. | GAP | No ejecutada. |
| O-07 | P2 | Multi-instancia sin duplicados. | GAP | Sin prueba. |
| O-08 | P1 | Staging con configuración tipo prod. | GAP | No documentado. |
| O-09 | P1 | Rollback probado. | GAP | Sin evidencia. |
| O-10 | P1 | Runbook de incidentes. | GAP | No existe. |
| O-11 | P1 | Migración Flyway con datos existentes. | GAP | Testcontainers solo desde cero. |
| O-12 | P2 | Recuperación ante desastre. | GAP | Sin RPO/RTO. |
| F-01 | P2 | Tablero renderiza pedidos. | PASS-E | `operacion.test.js` (92 checks). |
| F-02 | P2 | Alta/edición/borrado de líneas. | PASS-E | Frontend simulado. |
| F-03 | P2 | Producto repetido se fusiona. | PASS-E | Frontend simulado. |
| F-04 | P2 | Validación de cantidad/precio. | PASS-E | Frontend simulado. |
| F-05 | P2 | Aprobar envía payload correcto. | PASS-E | `fetch` simulado; no backend real. |
| F-06 | P2 | Badges/reintento de impresión. | PASS-E | Frontend simulado. |
| F-07 | P2 | Configuración de tiempos/autoimpresión. | PASS-E | Frontend simulado. |
| F-08 | P2 | Cola offline y sincronización. | PASS-E | Frontend simulado. |
| F-09 | P2 | SSE real/reconexión. | GAP | `EventSource` fake sin comportamiento. |
| F-10 | P2 | PWA en móvil/HTTPS/instalación. | BLOCKED | Sin dispositivo/navegador real. |
| F-11 | P3 | Accesibilidad. | GAP | No evaluada. |
| F-12 | P1 | Sin XSS por `innerHTML` en datos del pedido. | STATIC | Revisión positiva; no pentest. |
| D-01 | P1 | OSV de dependencias. | CONFIRMED | Consulta 2026-10-25; CVEs en Boot/Spring/Tomcat/PG/Spring AI. |
| D-02 | P2 | SBOM generado. | GAP | No existe. |
| D-03 | P1 | Escaneo de dependencias en CI. | GAP | No hay evidencia. |
| D-04 | P1 | Upgrade compatible probado. | BLOCKED | Requiere migración y staging. |
| D-05 | P3 | Licencias compatibles. | GAP | No revisado. |

## I. Casos de regresión que deben agregarse antes de corregir

Estos casos no se ejecutaron; se proponen como puerta de aceptación. Deben fallar con el código actual y pasar después del fix.

| ID | Caso | Resultado esperado |
|---|---|---|
| R-01 | Autoaprobar pedido vacío. | Sin venta, sin `print_job`, `REVIEW`. |
| R-02 | Autoaprobar línea sin cantidad. | Sin venta; no usar 1; `REVIEW`/422. |
| R-03 | Autoaprobar `requires_verification=true`. | Sin venta automática. |
| R-04 | Autoaprobar cliente minorista. | Sin venta automática. |
| R-05 | Autoaprobar cotización incompleta. | Sin venta; sin total nulo. |
| R-06 | Autoaprobar con impresora deshabilitada. | Sin `print_job`; revisión. |
| R-07 | Doble autoaprobación concurrente. | Una sola venta; idempotente. |
| R-08 | `validateOrder` sobre pedido no aprobado. | No crea venta. |
| R-09 | `reprintByOrderId` sin venta. | No autoaprueba. |
| R-10 | Reopen de venta impresa/despachada. | 409; venta intacta. |
| R-11 | Merge con `print_job` en `PRINTING`. | 409; sin borrado. |
| R-12 | Adición a pedido aprobado impreso. | Pedido nuevo/revisión; venta anterior intacta. |
| R-13 | Reset en perfil prod. | 404/403; datos intactos. |
| R-14 | Webhook con BD caída. | 5xx; reintento posterior procesa una vez. |
| R-15 | HMAC inválido/válido. | 403/200; sin bypass en prod. |
| R-16 | Duplicado de mensaje. | Un solo pedido. |
| R-17 | `statuses` de Meta. | Estado y pricing persistidos. |
| R-18 | Reimpresión durante `PRINTING`. | No duplica; lease respetado. |
| R-19 | Reinicio con `PRINTING`. | Estado `UNKNOWN`/recuperación segura; sin doble envío ciego. |
| R-20 | Producto inventado. | Candidato pendiente; no producto activo. |

## J. Limitaciones de la matriz

- No es una matriz de cobertura: no se midió JaCoCo ni se ejecutaron todos los caminos.
- Los `STATIC` son hallazgos de lectura; pueden cambiar si el código cambia.
- Los `BLOCKED` requieren hardware, credenciales, número, staging o autorización; no se simularon como si hubieran pasado.
- Los `FAIL-E` son timeouts de la suite, no necesariamente defectos funcionales; se documentan como evidencia no concluyente.
- Los `PASS-E` del frontend usan DOM/fetch simulados, no un navegador real.
- La matriz debe actualizarse después de cada corrección y de cada corrida de regresión.
