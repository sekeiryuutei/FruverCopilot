# Revisión de ajustes — 2026-11-02

## Alcance

Revisión de solo lectura del estado actual del repositorio:

- Último commit: `85667bb` (LID, fidelidad de nombres, pedidos por valor, reintento de impresión, cambio de modo WhatsApp).
- Cambios sin commitear: limitador HTTP, limitador de IA por WhatsApp, webhook, máscara de teléfono, origen en factura y ajustes de UI.
- No se modificó código, `.env`, credenciales ni historial Git.

## Resultado de pruebas

- Backend: **94 tests, 4 fallos, 0 errores** (sin `ExtractionEvalTest`, para evitar llamadas live).
- Frontend: **91 OK, 1 fallo**.
- No se probaron WhatsApp real, impresora física, backup/restore ni concurrencia.

## Hallazgos

### 1. Regresión real: precio manual se confunde con pedido por valor

**Archivos:** `SaleService.java` (aprobación normal y autoaprobación mayorista), `OrderEditService.java`, `ExtractionInterpreter.java`.

`manualPrice` se usa para dos cosas distintas:

- Pedido por valor: el valor es el total y `quantity`/`unit` vienen nulos.
- Corrección humana de precio: el valor es el precio unitario y la cantidad sigue existiendo.

El código actual considera pedido por valor a **cualquier** ítem con `manualPrice != null`:

```java
boolean isMoney = it.manualPrice() != null || "VALOR".equalsIgnoreCase(...);
BigDecimal qty = isMoney ? BigDecimal.ONE : ...;
BigDecimal subtotal = isMoney ? it.manualPrice() : ...;
```

Efecto: una orden normal de 2 LB con precio manual de $4.000 se guarda como 1 unidad y subtotal $4.000, no como 2 × $4.000 = $8.000.

**Evidencia:** `SaleFlowIntegrationTest.manualPriceIsSnapshottedWithCatalogPrice` falla en la línea 138. Espera subtotal $8.000 y recibe $4.000.

**Recomendación:** separar el marcador de “pedido por valor” del precio manual. Por ejemplo, usar `quantity == null`/un campo explícito, no `manualPrice != null`. Aplicar la misma corrección a `autoApproveWholesale`.

### 2. Seguridad: el bypass del webhook por IP interna no protege detrás de ngrok/Docker

**Archivos:** `WhatsAppWebhookController.java`, `HttpRateLimitFilter.java`, `SecurityConfig.java`.

- El webhook acepta `X-Bridge-Origin: fruver-baileys` si `request.getRemoteAddr()` es una IP interna.
- Con el backend publicado por Docker y ngrok, las peticiones externas pueden llegar con IP interna del proxy.
- Un cliente externo podría enviar ese encabezado y saltarse la firma HMAC si el backend lo ve como interno.
- `HttpRateLimitFilter` confía en el primer valor de `X-Forwarded-For` y en `X-Real-IP`; si el proxy no los sobrescribe, se pueden falsear para eludir el límite o bloquear a terceros.

**Recomendación:** no confiar en el encabezado solo por IP. Usar un secreto compartido exclusivo del bridge (HMAC distinto al de Meta), validar la firma siempre, o configurar proxies de confianza y tomar la IP de una fuente controlada.

### 3. Impresión: el fallback CASA → OFICINA cambia el significado de “fallo”

**Archivo:** `PrintService.java` (`fail`, líneas 201-221).

Al fallar CASA, el trabajo se reencola en OFICINA y se reinician los intentos. El test que espera `PRINT_FAILED` después de 3 intentos ahora recibe `PRINT_PENDING`.

**Evidencia:** `SaleFlowIntegrationTest.printerFailureKeepsSaleAndReprintDoesNotCreateAnotherSale` falla en la línea 157.

**Recomendación:** definir cuándo un fallo es definitivo y cuándo se alerta al operador. Si el fallback es intencional, actualizar el test y revisar que la venta no quede mostrando “IMPRIMIENDO” indefinidamente. Si no, limitar el fallback y conservar `PRINT_FAILED` para la alerta.

### 4. Factura: se dejaron de imprimir las observaciones

**Archivo:** `TicketFormatter.java`.

El formateador ya no incluye `OBS:` ni las notas del pedido. El test `TicketAndConfirmationTest.ticketContainsRequiredFieldsAndFitsPaperWidth` sigue esperando `OBS: Sin cilantro` y falla.

**Recomendación:** decidir si fue un cambio de diseño intencional. Si lo fue, actualizar el test; si no, restaurar las observaciones porque pueden contener horario, timbre o indicaciones de entrega.

### 5. Prompt con reglas contradictorias

**Archivo:** `SystemPrompts.java`.

Hay instrucciones que pueden producir extracciones inconsistentes:

- “Normaliza siempre al producto del catálogo” convive con “usa exactamente el nombre genérico” (`Papa`, `Aguacate`). Si el catálogo no tiene ese nombre genérico, el pedido puede quedar sin producto asociado.
- “2 tomates” se define como cantidad 1 y unidad LB en una sección, pero la regla de “cantidad sin unidad” diría cantidad 2 y unidad nula.
- El prompt de imagen asigna 1.0 y `ambiguo=true` cuando falta cantidad; el prompt de texto pide `null` y `ambiguo=false`.

**Recomendación:** dejar una sola regla por caso y probarla con casos sintéticos antes del piloto. No cambiar el prompt sin medir la tasa de revisión manual.

### 6. Riesgos menores

- **Límite de IA agresivo:** `WhatsAppSecurityLimiter` permite 4 extracciones en 5 minutos y 10 por hora; un cliente que corrige varias veces puede terminar en `BLOCKED_SPAM` y revisión manual.
- **Duplicados:** el limiter calcula el hash sobre todo el texto acumulado, no sobre el mensaje entrante; la detección de mensajes repetidos pierde efectividad.
- **Memoria:** los mapas de historiales por teléfono/IP no se purgan; con muchos números únicos crecen sin límite.
- **Datos personales:** `whatsapp-bridge/src/index.js` contiene un LID y un teléfono personal hardcodeados como semilla. Deberían salir a configuración y no versionarse.
- **Frontend:** `isMoneyOrder` marca como pedido por valor cualquier ítem con precio manual >= 500 y cantidad <= 1; con cantidades fraccionarias puede mostrar un subtotal incorrecto.
- **Teléfono:** `TicketFormatter` no quita el prefijo `+57`; un número guardado como `+573001234567` se oculta en la tirilla aunque sea válido.

## Clasificación de los 4 fallos del backend

| Fallo | Clasificación |
|---|---|
| `manualPriceIsSnapshottedWithCatalogPrice` | Regresión real del último commit |
| `printerFailureKeepsSaleAndReprintDoesNotCreateAnotherSale` | Cambio de comportamiento; test no actualizado o fallo oculto |
| `orderDispatchAndWhatsAppInvoiceFlow` | Entorno: no hay puente WhatsApp en `http://whatsapp-bridge:3000`; el envío falla y no se registra el mensaje |
| `ticketContainsRequiredFieldsAndFitsPaperWidth` | Test obsoleto por eliminación de `OBS`, o pérdida no deseada de observaciones |

## Fallo de frontend

`Pedido 10009 tiene botón REINTENTAR IMPRESIÓN`: el test busca el texto `REINTENTAR IMPRESIÓN`, pero el botón actual dice `REINTENTAR / IMPRIMIR`. Es una expectativa desactualizada, salvo que se quiera conservar ese texto exacto.

## Prioridad sugerida

1. Corregir la confusión `manualPrice` / pedido por valor.
2. Cerrar el bypass del webhook y la confianza en encabezados de proxy.
3. Definir el comportamiento final del fallback de impresión.
4. Decidir si las observaciones deben volver a la factura.
5. Unificar el prompt y ajustar el limiter antes del piloto.
