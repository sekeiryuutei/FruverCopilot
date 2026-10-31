# Auditoría de pruebas — FruverCopilot

**Fecha de corte:** 2026-10-25 13:01 -05 (America/Bogota)  
**Commit auditado:** `dc5d842` (rama `mayorista`)  
**Modo:** pruebas locales seguras. No se enviaron mensajes reales de WhatsApp, no se imprimieron pedidos reales, no se accedió a producción y no se modificó código.  
**Entorno:** Windows, PowerShell; OpenJDK Temurin 21.0.12.1; Maven 3.9.16; Node.js 24.20.0; Docker 29.7.2.

## 1. Resultado ejecutivo

| Suite | Comando | Resultado | Lectura |
|---|---|---:|---|
| Frontend/PWA | `node frontend-tests/operacion.test.js` | **92 OK, 0 FAIL, `TODO OK`** | Lógica de la pantalla contra DOM y `fetch` simulados. No prueba backend, red, SSE real ni hardware. |
| Backend | `mvn -B test` en `backend/` | **94 tests, 82 sin error, 12 errores, 0 fallos de aserción, 0 omitidos; `BUILD FAILURE`** | Los 12 errores son timeouts de Awaitility. No hubo una aserción funcional fallida, pero 12 escenarios críticos quedaron sin validar. |
| IA real | Incluida en `mvn test` | **3 pruebas ejecutadas con Gemini real** | Efecto secundario no deseado: el test carga `.env` y se autohabilita con claves reales. No se repitió. |
| WhatsApp real | No ejecutado | **Bloqueado** | Requiere número, credenciales, plantillas y autorización. |
| Impresora física | No ejecutado | **Bloqueado** | Requiere equipo ESC/POS en red; `PRINTER_ENABLED=false` en el entorno local. |
| Backup/restore | No ejecutado | **Bloqueado** | No hay evidencia de respaldo reciente ni entorno de restauración. |

**Conclusión:** la suite **no está verde** y no puede usarse como evidencia de liberación. Los 92 chequeos del frontend son valiosos, pero cubren una capa simulada. Los 82 tests backend que terminaron sin error incluyen unidades, dominio, integración con PostgreSQL real y flujos manuales; sin embargo, los 12 timeouts impiden afirmar que los caminos de pedido, venta, anexo y multimodal funcionen de extremo a extremo.

## 2. Detalle por clase de prueba

Los tiempos son los reportados por Surefire. La columna “Error” indica `ConditionTimeoutException` de Awaitility, no una aserción de negocio.

| Clase | Tests | Error | Tiempo | Observación |
|---|---:|---:|---:|---|
| `ExtractionInterpreterTest` | 19 | 0 | 0,086 s | Interpretación/validación de salida de IA. Pasa. |
| `MockOrderExtractorTest` | 4 | 0 | 0,138 s | Extractor simulado. Pasa. |
| `OrderProcessingInquiryTest` | 9 | 0 | 0,037 s | Clasificación de saludos, precios, fotos y demoras. Pasa. |
| `TicketAndConfirmationTest` | 4 | 0 | 0,021 s | Formato de ticket/confirmación. Pasa. |
| `MoneyAndTokensTest` | 5 | 0 | 0,004 s | Dinero y tokens. Pasa. |
| `ReviewEvaluatorTest` | 9 | 0 | 0,008 s | Reglas de revisión. Pasa. |
| `AiProviderCascadeAndSaturationTest` | 3 | 0 | 16,97 s | Cascada/saturación de proveedores. Pasa. |
| `GeminiAdaptersTest` | 2 | 0 | 0,009 s | Adaptadores Gemini. Pasa. |
| `GeminiKeyPoolTest` | 4 | 0 | 0,011 s | Pool de claves. Pasa. |
| `ImageOptimizerTest` | 2 | 0 | 0,334 s | Optimización de imagen. Pasa. |
| `DemoDataSeederIntegrationTest` | 2 | 0 | 12,25 s | Testcontainers PostgreSQL; seed y reset en base de prueba. Pasa. |
| `ExtractionEvalTest` | 3 | 0 | 28,83 s | **Gemini real**: 3 llamadas externas. Pasa, pero no debe correr por defecto. |
| `MultimodalBatchBurstIntegrationTest` | 1 | 1 | 15,06 s | Ráfaga audio/foto/texto/foto; timeout a los 10 s. |
| `SaleFlowIntegrationTest` | 17 | 8 | 128,2 s | Flujo de venta; 8 timeouts. |
| `WholesaleFlowIntegrationTest` | 6 | 3 | 45,42 s | Flujo mayorista; 3 timeouts. |
| `ControllersUnitTest` | 4 | 0 | 0,681 s | Controladores con mocks; incluye verificación GET del webhook. |

**Total actual:** 94 tests, 12 errores, 0 fallos, 0 omitidos. La suma de tiempos de las clases es aproximadamente 248 s, sin contar el arranque de Maven/Spring.

### 2.1 Reporte obsoleto que no debe confundirse con esta corrida

En `backend/target/surefire-reports/` existe un `ControllersIntegrationTest.txt` con **7 fallos** (`404`/`401`) de una corrida anterior. La fuente `ControllersIntegrationTest.java` **ya no existe** en el árbol actual y esos 7 casos **no forman parte de los 94 tests de esta corrida**. Es un artefacto viejo; no debe usarse como evidencia del estado actual ni sumarse al total. Se recomienda limpiar `target/` antes de publicar reportes, sin borrar código ni historiales.

## 3. Los 12 errores: causa raíz

Los 12 errores son `ConditionTimeoutException` de Awaitility con `atMost(10, SECONDS)`. La causa es una combinación de configuración de pruebas y precedencia del debounce:

1. **`SaleFlowIntegrationTest` y `WholesaleFlowIntegrationTest` no configuran `app.conversation.debounce-seconds`.** Usan el default de `application.yml:67`, que es **20 s**. Esperan 10 s. El timeout está predeterminado.
2. **`MultimodalBatchBurstIntegrationTest` sí configura `app.conversation.debounce-seconds=0`**, pero `OrderBatchingCoordinator.java:53-60` consulta primero `PrinterConfigService.get().customerDebounceSeconds()`. Esa lectura toma `printer_setting.customer_debounce_seconds`, creado por `V17__ai_debounce_setting.sql` con **20**. El 0 de la prueba queda subordinado al 20 de la base.
3. **`PrinterConfigService`** inicia el valor con la propiedad, pero si la fila de `printer_setting` tiene un valor `>= 0`, lo reemplaza. Por eso el valor persistido gana.
4. **En producción**, cambiar `CUSTOMER_DEBOUNCE_SECONDS` en `.env` no basta si ya existe la fila de `printer_setting`: el valor de base de datos tiene precedencia. Esto es un problema de operación, no solo de tests.

**Corrección de pruebas recomendada:** fijar el debounce a 0 en **todas** las pruebas de integración y, además, ajustar la fila de `printer_setting` en el fixture de prueba (o inyectar una configuración de prueba que no dependa de la base). Subir el `await` a 30 s solo escondería el problema y haría la suite más lenta.

**Corrección de producto recomendada:** definir explícitamente la precedencia (`env` vs. base de datos), documentarla y exponerla en la UI. Si la base es la fuente de verdad, no debe existir una variable de entorno que parezca controlar el valor y no lo haga.

### 3.1 Errores por prueba

| Clase | Prueba | Línea de espera |
|---|---|---:|
| `MultimodalBatchBurstIntegrationTest` | `rafagaCompletaAudioFotoTextoFotoSeConsolidaEnUnSoloTokenBatch` | 105 |
| `SaleFlowIntegrationTest` | `consecutiveMessagesAttachToSameActiveOrderWithoutCreatingDuplicates` | 216 |
| `SaleFlowIntegrationTest` | `duplicateExternalMessageCreatesOneOrder` | 186 |
| `SaleFlowIntegrationTest` | `sameProductOnTwoLinesIsRejectedByTheServer` | 227 |
| `SaleFlowIntegrationTest` | `customerReplyAttachesToTheWaitingOrderInsteadOfCreatingANewOne` | 196 |
| `SaleFlowIntegrationTest` | `approvingTwiceCreatesOnlyOneSale` | 107 |
| `SaleFlowIntegrationTest` | `staleVersionIsRejected` | 116 |
| `SaleFlowIntegrationTest` | `wholesaleCustomerGetsWholesalePriceAndSnapshotSurvivesPriceChange` | 92 |
| `SaleFlowIntegrationTest` | `mergeOrderIntoPendingDispatchOrder` | 379 |
| `WholesaleFlowIntegrationTest` | `subsequentAdditionsMergeItemsWithoutOverwriting` | 213 |
| `WholesaleFlowIntegrationTest` | `imageOrderAutoApprovesAndEnqueuesPrintJob` | 78 |
| `WholesaleFlowIntegrationTest` | `ticketByOrderOnUnapprovedOrderDoesNotApproveNorCreateSale` | 177 |

## 4. Qué sí quedó validado

- **Frontend/PWA (92 chequeos):** render del tablero, alta/edición/borrado de líneas, fusión de producto repetido, validación de cantidades y precios, aprobación, badges de impresión, reintentos, configuración de tiempos, cola offline y sincronización. Es una prueba de lógica contra DOM y `fetch` simulados; no reemplaza una prueba en navegador real.
- **Unidad/dominio:** interpretación de IA, evaluación de revisión, dinero/tokens, formato de ticket, clasificación de consultas, pool de claves y cascada de proveedores.
- **Integración con PostgreSQL real (Testcontainers):** varios flujos de venta que no dependen del debounce terminaron sin error, incluidos precio manual, unidad incompatible, fallo de impresora/reimpresión, valores negativos/absurdos, zonas de entrega, despacho/factura, seed de demo y apertura de pedido.
- **Extracción con IA real:** tres invariantes pasaron (inyección de prompt tratada como dato, papa ambigua, dinero no es cantidad). **No se repitió** por costo y por uso de credenciales reales.
- **Webhook GET:** token correcto devuelve 200/challenge; token incorrecto devuelve 403 (`ControllersUnitTest`). Esto no prueba la firma HMAC del POST.

## 5. Qué no quedó validado

| Área | Casos no validados | Motivo |
|---|---|---|
| Webhook | HMAC real con firma válida/inválida, replay, BD caída, `statuses`, `phone_number_id`, tipos no soportados | No hay prueba de integración; el unit test usa el bypass `ignore` y firma nula. |
| Pedido | Creación desde texto, mensajes consecutivos, respuesta a pedido en espera, duplicado | 8 timeouts de `SaleFlow`. |
| Venta | Aprobación doble, versión obsoleta, precio mayorista con snapshot, dos líneas del mismo producto, merge | Timeouts de `SaleFlow`/`Wholesale`. |
| Autoaprobación | Pedido vacío, dudoso, sin cantidad, minorista, impresora apagada, `requires_verification=true` | Solo una prueba pasa y **codifica el defecto**; faltan las negativas. |
| Multimodal | Audio, foto, lote audio+foto+texto | Timeout de `MultimodalBatchBurst`. |
| Impresión | Papel, corte, cajón, ACK ESC/POS, reinicio en `PRINTING`, concurrencia, doble worker | Sin hardware ni pruebas de estados. |
| WhatsApp saliente | Ventana de 24 h, plantillas, opt-out, estados, `wamid`, precio/categoría | Sin número/plantillas ni webhook de estados. |
| Seguridad | 401/403 por rol, CSRF real, rate limit, reset en prod, bootstrap | No hay pruebas de integración de seguridad; el reporte viejo no cuenta. |
| Operación | Backup/restore, carga, multi-instancia, caos, recuperación | Sin entorno ni evidencia. |
| Navegador | PWA real, móvil, SSE real, accesibilidad | El test usa DOM falso. |

## 6. Defectos de diseño de la suite

1. **Pruebas que protegen el comportamiento incorrecto.** `WholesaleFlowIntegrationTest.doubtfulOrderSetsVerificationFlag` espera que el pedido quede `APPROVED` y solo comprueba la bandera; `SaleFlowIntegrationTest.reopenOrderAllowsAddingMoreItems` espera `saleId=null` después de reabrir. Ambas pasan con el comportamiento destructivo/inseguro. Al corregir P0-02/P0-03, estas pruebas deben cambiar de expectativa, no “arreglarse” para que sigan pasando.
2. **Test live dentro de la suite por defecto.** `ExtractionEvalTest` carga `.env`, copia claves a propiedades del sistema y se habilita si encuentra una clave real. En esta corrida hizo **3 llamadas a Gemini**. Debe tener `@Tag("live")`, un perfil Maven separado y ninguna carga automática de `.env` en CI.
3. **Dependencia silenciosa de Docker.** Las pruebas de integración usan `@Testcontainers(disabledWithoutDocker = true)`. Sin Docker se **omiten**, no fallan; un CI sin Docker puede mostrar una suite “verde” con menos cobertura. Hay que reportar los omitidos y hacer obligatorio Docker en el job de integración.
4. **Configuración duplicada por clase.** Cada `@SpringBootTest` repite propiedades; no hay un `application-test.yml` central. Eso facilita inconsistencias como el debounce.
5. **Sin medición de cobertura.** No se generó JaCoCo ni otro reporte de cobertura. No se puede afirmar un porcentaje de líneas/ramas cubiertas.
6. **Sin pruebas de contrato con Meta.** No hay WireMock/servidor simulado para POST, errores 4xx/5xx, `statuses`, `wamid`, plantillas o ventana de 24 h.
7. **Sin pruebas de concurrencia reales.** `FOR UPDATE`/`SKIP LOCKED` están en el código, pero no hay dos workers/instancias compitiendo en la suite.
8. **Sin pruebas de migraciones desde una base existente.** Flyway V1–V18 se aplica desde cero en Testcontainers; no se probó un upgrade con datos reales.

## 7. Pruebas bloqueadas o no ejecutables en esta auditoría

| Prueba | Requisito | Estado | Riesgo si no se hace |
|---|---|---|---|
| Mensaje real de WhatsApp (texto/audio/imagen) | Número de prueba, app secret, token, webhook público, opt-in | Bloqueada | No se valida Meta, entrega, ventana ni calidad. |
| Plantilla utility/marketing | Plantilla aprobada y categoría correcta | Bloqueada | Un envío fuera de ventana fallará o se cobrará como marketing. |
| Estados de entrega/lectura | Webhook `statuses` y `wamid` | Bloqueada | No se puede conciliar entrega ni costo. |
| Impresión física | Impresora ESC/POS, papel, red, corte, cajón | Bloqueada | `PRINTED` sigue siendo una promesa no verificada. |
| Falta de papel/corte/red | Hardware y control de fallos | Bloqueada | No se conoce el comportamiento real ni la recuperación. |
| Backup/restore | Backup real y entorno staging | Bloqueada | No se conoce RPO/RTO ni integridad. |
| Carga/concurrencia multi-instancia | Entorno con réplicas y generador de carga | Bloqueada | No se validan duplicados, locks ni cuellos de botella. |
| Pentest | Autorización y alcance | Bloqueada | La revisión es estática, no un pentest. |
| PWA en dispositivo real | Navegador móvil, HTTPS, service worker | Bloqueada | El DOM falso no prueba instalación, caché ni SSE real. |

## 8. Recomendaciones inmediatas para la suite

1. **No volver a ejecutar `mvn test` sin aislar `ExtractionEvalTest`.** Usar `-Dtest='!ExtractionEvalTest'` o, mejor, un perfil que excluya `@Tag("live")`; no cargar `.env` automáticamente.
2. **Hacer determinista el debounce:** propiedad 0 en todas las pruebas de integración y actualización explícita de `printer_setting` en el fixture. Después, volver a correr la suite.
3. **Agregar primero las pruebas de regresión P0** que deben fallar con el código actual: autoaprobación con duda/vacío/sin cantidad, venta inmutable, reset en prod, webhook con BD caída y HMAC real.
4. **Separar unitarias, integración y live** en perfiles Maven/CI. El job de integración debe exigir Docker y reportar omitidos.
5. **Añadir pruebas de contrato con Meta** (WireMock) y un fake de impresora con estados/errores.
6. **Añadir JaCoCo** y publicar cobertura por paquete; no fijar un umbral alto antes de estabilizar.
7. **Documentar el resultado real:** 94 tests, 12 errores, 0 fallos, 0 omitidos, `BUILD FAILURE`. No reportar “todo pasó” por tener 0 fallos de aserción.

## 9. Veredicto de pruebas

**La suite backend no es confiable todavía como puerta de producción.** Los timeouts son explicables y corregibles, pero mientras existan no se validan pedidos, ventas, merges, reaperturas ni multimodal. Además, las pruebas que sí pasan incluyen expectativas que normalizan los defectos P0. La estrategia correcta no es “hacer pasar la suite”, sino **cambiar primero las reglas de negocio y luego exigir pruebas que fallen con el comportamiento actual**.

La matriz detallada de casos, incluyendo los estáticos y bloqueados, está en [MATRIZ_CASOS_PRUEBA.md](MATRIZ_CASOS_PRUEBA.md). El plan de corrección y regresión está en [PLAN_CORRECCIONES.md](PLAN_CORRECCIONES.md).
