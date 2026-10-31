# Optimización de costos de WhatsApp — FruverCopilot

**Fecha:** 2026-10-25  
**Mercado analizado:** Colombia (+57)  
**Fuente principal:** [Pricing on the WhatsApp Business Platform](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing), consultada el 2026-10-25.  
**Vigencia:** tarifas efectivas desde el **2026-10-01** (zona horaria de la cuenta de mensajería).  
**Alcance:** costos de Meta por mensajes entregados. Los costos de IA, STT, infraestructura y soporte se analizan aparte porque el sistema no los mide.

> **Advertencia:** las cifras de este documento son escenarios de presupuesto, no la factura real. No se accedió a Meta Billing, a la cuenta de producción ni al tráfico real. Antes de presupuestar, verificar la tarifa vigente, la moneda de facturación, impuestos y tipo de cambio.

## 1. Resumen ejecutivo

1. Meta cobra **por mensaje entregado**, según la categoría y el país del destinatario. Los mensajes entrantes del cliente **no se cobran**.
2. Desde el **2026-10-01**, los mensajes de **service** y **utility** que antes eran gratis se cobran por mensaje. Cada número de negocio tiene **1.000 mensajes service gratis al mes**, sin acumulación.
3. En Colombia, la tarifa listada es:
   - **Marketing:** USD 0,0125 por mensaje entregado (COP 46,0227).
   - **Utility, authentication y service:** USD 0,0008 por mensaje entregado (COP 2,9455).
4. **Los emojis no reducen el costo.** El precio depende de la categoría y del país, no de la cantidad de caracteres ni de los emojis. Un mensaje con emojis sigue siendo un mensaje service/utility/marketing.
5. El mayor riesgo de costo no es el texto del pedido: es **enviar mensajes de más**, **duplicar reintentos**, **clasificar marketing como service** o lanzar campañas masivas sin opt-in. Un mensaje marketing cuesta **15,6 veces** más que uno service en Colombia.
6. El sistema actual no registra `wamid`, estados de entrega, categoría ni `pricing`; por tanto **no puede conciliar el costo real**. La primera inversión debe ser medición, no “optimización” a ciegas.
7. El costo de IA/STT no está instrumentado. Puede superar el costo de Meta en pedidos con audio/foto, pero no se incluye en las cifras de este informe.

## 2. Cómo cobra Meta (reglas relevantes)

| Regla | Efecto en FruverCopilot |
|---|---|
| Se cobra solo por mensaje **entregado** | Un envío fallido o rechazado no se cobra. Un `SENT` HTTP 2xx no garantiza entrega ni costo. |
| Se cobra por categoría y mercado | La tarifa depende del código de país del cliente. Si el cliente no es +57, aplica otra tarifa. |
| Entrantes gratis | Los mensajes del cliente a la empresa no se cobran. |
| Service = respuesta no-plantilla dentro de la ventana de 24 h | Los textos/imágenes que envía la IA o el operador dentro de la conversación son service. |
| Utility = plantilla transaccional | Confirmaciones, pedido listo, factura o despacho por plantilla. Se cobra aunque se envíe dentro de la ventana. |
| Marketing = plantilla promocional | Promociones, remarketing, catálogos. Es la categoría más cara. |
| Authentication = verificación de identidad | No se usa hoy. |
| 1.000 service gratis/mes por número | Se aplica al número de negocio, no a la WABA completa. No se acumula. Desde el mensaje 1.001 se cobra. |
| FEP (Free Entry Point) | Si el cliente llega desde un anuncio Click-to-WhatsApp (Android/iOS), y la empresa responde dentro de la ventana, se abre una ventana de hasta 7 días sin cargo para marketing/utility/auth/service. Meta Business Agent sigue cobrando. |
| Volume tiers | Solo aplican a utility/authentication, no a service ni marketing. A bajo volumen no son relevantes. |
| Meta Business Agent | USD 2,00 por 1 millón de tokens; Meta estima 4–5 centavos por mensaje típico. No es la tarifa de service. |
| Actualización de tarifas | Meta puede actualizar el 1 de enero, abril, julio y octubre. Hay que revalidar antes de cada presupuesto. |

## 3. Tarifas de Colombia

Tarifas listadas por Meta, efectivas desde el 2026-10-01. Valores extraídos de la tarjeta oficial en USD y COP.

| Categoría | USD por mensaje entregado | COP por mensaje entregado | ¿Se usa hoy? |
|---|---:|---:|---|
| Marketing | 0,0125 | 46,0227 | No implementado; solo si se agregan campañas/plantillas promocionales. |
| Utility | 0,0008 | 2,9455 | No implementado; se usaría para confirmaciones/facturas por plantilla. |
| Authentication | 0,0008 | 2,9455 | No se usa. |
| Service | 0,0008 | 2,9455 | **Sí**: respuestas libres de la IA/operador dentro de la ventana de 24 h. |
| Authentication-international | n/a para Colombia | n/a | No aplica. |
| Meta Business Agent | USD 2,00 / 1M tokens (≈ USD 0,04–0,05/mensaje) | — | No se usa. |
| Entrante del cliente | 0 | 0 | Siempre. |

**Notas:**

- La tarifa de service es igual a utility/authentication en Colombia; la diferencia práctica es la **categoría** y la **ventana**. No conviene “forzar” una plantilla utility solo para evitar service si el mensaje puede ir como service dentro de la ventana: el precio es el mismo, pero la plantilla agrega complejidad y posibles cargos de categoría.
- Si el cliente es de otro país, la tarifa de Colombia no aplica. Usar el mercado del código de llamada.
- Los valores están en la moneda de la tarjeta; la cuenta puede facturar en USD o COP. Impuestos, tipo de cambio y redondeo son aparte.
- Los volume tiers pueden reducir utility/authentication en volúmenes altos, pero no service/marketing. No se asumen en los escenarios.

## 4. Fórmula de costo

Para un número de negocio y un mes, con mensajes **entregados**:

```text
C_meta_USD =
    max(0, S_del - 1000) × 0,0008
  + U_del × 0,0008
  + A_del × 0,0008
  + M_del × 0,0125
```

```text
C_meta_COP =
    max(0, S_del - 1000) × 2,9455
  + U_del × 2,9455
  + A_del × 2,9455
  + M_del × 46,0227
```

Donde:

- `S_del` = mensajes **service entregados** en el mes.
- `U_del` = mensajes **utility entregados**.
- `A_del` = mensajes **authentication entregados**.
- `M_del` = mensajes **marketing entregados**.
- Los mensajes en FEP no se cobran; si se usan, verificar en el estado si consumen o no el cupo gratuito.
- No se suman los entrantes.
- Si se usa authentication-international o Meta Business Agent, agregar su fórmula/consumo por separado.

**Costo total del canal:**

```text
C_total = C_meta + C_ai + C_stt + C_infra + C_soporte
Costo_por_pedido = C_total / pedidos_entregados
```

Hoy `C_ai`, `C_stt` y `C_meta` real no se pueden calcular con precisión porque no hay ledger de tokens, estados ni `pricing`. Las cifras siguientes son solo `C_meta` estimado.

## 5. Qué hace hoy el sistema y cómo se traduce en categorías

| Comportamiento actual | Categoría probable | Observación de costo |
|---|---|---|
| Respuesta de IA a un saludo/consulta | Service | Dentro de la ventana de 24 h. Cada mensaje cuenta. |
| Aviso “hemos recibido tu pedido” y luego confirmación | Service | Son dos mensajes si se envían en momentos distintos; se pueden consolidar. |
| Factura por texto (`sendInvoiceWhatsApp`) | Service | Se envía solo si el pedido no se trata como mayorista. |
| Aviso de despacho por texto | Service | Se envía solo si `autoPrintWholesale=false`. |
| Imagen con caption | Service | Si la imagen falla y cae a texto, podrían ser dos intentos; si ambos se entregan, dos cargos. |
| Reenvío manual de factura | Service | Cada clic puede generar otro mensaje; no hay idempotencia persistente. |
| Confirmación por plantilla | Utility | No implementado. Si se agrega, se cobra por mensaje incluso dentro de la ventana. |
| Campaña promocional | Marketing | No implementado. Sería la categoría más cara. |
| Fuera de la ventana de 24 h | Falla si es texto libre | No hay plantilla de respaldo; no se cobra, pero no se entrega. |

**Consecuencia:** con la configuración observada (`AUTO_PRINT_WHOLESALE=true` por defecto), muchos pedidos mayoristas no envían factura ni aviso de despacho; el costo de Meta puede ser bajo, pero también la comunicación al cliente es incompleta. Si se activa el envío para todos, el costo sube por pedido. Es una decisión de negocio, no solo técnica.

## 6. Escenarios de costo

### 6.1 Supuestos

- Un solo número de negocio en Colombia.
- Sin FEP, sin volume tiers, sin impuestos ni tipo de cambio.
- Todos los mensajes salientes son **service** y se entregan.
- No hay marketing ni utility.
- No se incluye IA/STT.
- “Mensajes por pedido” incluye respuestas de IA, confirmaciones, factura y despacho, según el flujo.

### 6.2 Costo mensual por pedidos y mensajes service

| Pedidos/mes | Service/pedido | Service entregados | Costo USD | Costo COP |
|---:|---:|---:|---:|---:|
| 1.000 | 1 | 1.000 | 0,00 | 0 |
| 1.000 | 2 | 2.000 | 0,80 | 2.945,5 |
| 1.000 | 3 | 3.000 | 1,60 | 5.891,0 |
| 5.000 | 1 | 5.000 | 3,20 | 11.782,0 |
| 5.000 | 2 | 10.000 | 7,20 | 26.509,5 |
| 5.000 | 3 | 15.000 | 11,20 | 41.237,0 |
| 10.000 | 1 | 10.000 | 7,20 | 26.509,5 |
| 10.000 | 2 | 20.000 | 15,20 | 55.964,5 |
| 10.000 | 3 | 30.000 | 23,20 | 85.419,5 |
| 50.000 | 1 | 50.000 | 39,20 | 144.329,5 |
| 50.000 | 2 | 100.000 | 79,20 | 291.604,5 |
| 50.000 | 3 | 150.000 | 119,20 | 438.879,5 |

**Lectura:** a escala de 10.000 pedidos, el costo de service es bajo en Colombia (USD 15–23/mes con 2–3 mensajes por pedido). El problema económico no es el precio unitario; es la **cantidad de mensajes** y, sobre todo, **marketing no controlado**. A 50.000 pedidos, 3 mensajes/pedido cuestan USD 119,20/mes; sigue siendo menor que una campaña masiva.

### 6.3 Costo de una campaña marketing

| Mensajes marketing entregados | Costo USD | Costo COP |
|---:|---:|---:|
| 1.000 | 12,50 | 46.022,7 |
| 5.000 | 62,50 | 230.113,5 |
| 10.000 | 125,00 | 460.227,0 |
| 50.000 | 625,00 | 2.301.135,0 |

**Lectura:** 10.000 mensajes marketing cuestan **USD 125** (COP 460.227), equivalentes a **156.250 mensajes service** en el tramo cobrado. Una campaña mal segmentada puede costar más que toda la operación transaccional del mes. No lanzar marketing sin opt-in, segmentación y medición de retorno.

### 6.4 Escenario mixto

Ejemplo: 10.000 pedidos, 19.000 service entregados (1,9/pedido), 2.000 utility (confirmaciones por plantilla) y 1.000 marketing.

```text
Service:  max(0, 19.000 - 1.000) × 0,0008 = 14,40 USD
Utility:  2.000 × 0,0008 = 1,60 USD
Marketing: 1.000 × 0,0125 = 12,50 USD
Total Meta = 28,50 USD
```

En COP: `18.000 × 2,9455 + 2.000 × 2,9455 + 1.000 × 46,0227 = 104.932,7 COP`.

El mismo escenario con 5.000 marketing en vez de 1.000 sube a **USD 78,50** (COP 289.023,5), es decir, el marketing domina. Hay que calcularlo con la fórmula y no con intuición.

## 7. Palancas de optimización, en orden de impacto

### 7.1 Reducir la cantidad de mensajes, no los caracteres

El precio es por mensaje entregado. Acortar un texto o quitar emojis **no reduce** el costo de Meta. Lo que sí reduce es enviar **menos mensajes**:

- consolidar “recibido” + “confirmado” en un solo mensaje cuando el pedido ya está claro;
- no enviar un segundo mensaje si el cliente ya recibió la información;
- unificar factura y estado de despacho cuando el negocio lo permita;
- evitar respuestas automáticas repetidas por ráfagas.

**Cuidado:** consolidar puede afectar la experiencia. Medir satisfacción y tasa de reclamos, no solo costo.

### 7.2 Persistir idempotencia y evitar duplicados

- `ConversationService` deduplica solo en memoria durante 8 segundos. Un reinicio, dos instancias o un clic repetido pueden duplicar el envío y el cargo.
- `sendInvoiceWhatsApp` no tiene cooldown ni clave de idempotencia; el endpoint manual puede reenviar la factura muchas veces.
- Los reintentos ante timeout de HTTP pueden duplicar un mensaje que Meta sí entregó.
- Implementar ledger persistente por `(order_id, kind, version)` y `wamid`; no reintentar a ciegas cuando el resultado es ambiguo.

### 7.3 Usar la ventana de 24 h y la categoría correcta

- Si el cliente acaba de escribir, la respuesta libre es service. No convertirla en marketing.
- Si la ventana está cerrada, usar una plantilla **utility** para información transaccional; no marketing.
- No usar una plantilla marketing para una confirmación de pedido: además de costar más, puede violar la política de categoría.
- Si se usa FEP/CTWA, aprovechar la ventana de 7 días; no asumir que todo el tráfico orgánico es FEP.

### 7.4 Aprovechar el cupo de 1.000 service

- El cupo es por número de negocio y por mes; no se acumula.
- A 1.000 pedidos con 1 mensaje service cada uno, el costo de service puede ser cero.
- A 1.000 pedidos con 2 mensajes, solo 1.000 se cobran (USD 0,80).
- Monitorear el consumo y alertar al 80% del cupo. Si hay varios números, cada uno tiene su cupo; no sumarlos como si fueran uno.

### 7.5 Evitar el doble cargo por imagen + fallback

`WhatsAppCloudClient.sendImage` intenta imagen y, si falla, envía texto. Si la imagen se entregó pero la respuesta HTTP se perdió, el fallback puede entregar un segundo mensaje y cobrar dos. Preferir:

- una sola llamada con imagen y caption;
- si el resultado es ambiguo, consultar estado/`wamid` antes de reenviar;
- no usar fallback automático en un timeout; registrar `UNKNOWN` y decidir.

### 7.6 Marketing solo con opt-in y retorno medido

- Marketing cuesta 15,6 veces más que service en Colombia.
- No enviar promociones a toda la base; segmentar por consentimiento, frecuencia y valor.
- Medir conversión y costo por pedido atribuido; si no hay retorno, no enviar.
- Respetar opt-out y límites de frecuencia; los bloqueos/reportes pueden degradar la calidad del número y la entregabilidad.

### 7.7 Medir y conciliar antes de optimizar

Implementar un ledger de mensajes con:

- `wamid`, dirección, categoría, plantilla, `pricing.billable`, `pricing.type`, `pricing.category`;
- estado (`sent`, `delivered`, `read`, `failed`), timestamps y errores;
- ventana/FEP, número emisor, país del destinatario;
- orden/cliente, reintento de, costo estimado y costo conciliado.

Luego comparar contra Meta Billing. Sin esto, cualquier “optimización” es una suposición.

### 7.8 IA/STT: medir aparte

El costo de Gemini/STT no se deduce del precio de WhatsApp. Palancas:

- debounce y batch para no llamar a la IA por cada mensaje;
- cache de contexto/catálogo;
- modelos más pequeños para consultas simples;
- límites de tokens y presupuesto por cliente;
- no reenviar audio/foto a la IA si ya hay una transcripción válida.

No se incluyen cifras de IA porque no hay telemetría de tokens; poner un número sería inventarlo.

### 7.9 Meta Business Agent: no asumir que es más barato

Meta estima 4–5 centavos por mensaje típico (USD 0,04–0,05), muy por encima de USD 0,0008 de service en Colombia. Incluye procesamiento de IA, así que solo tiene sentido si reemplaza el costo actual de IA + mensajes y mejora la experiencia. No es una palanca de ahorro automática.

### 7.10 Volume tiers y FEP

- Volume tiers: solo utility/authentication; no service/marketing. A bajo volumen, el ahorro es marginal. No diseñar la operación alrededor de ellos.
- FEP: puede eliminar el cargo de marketing/utility/service por hasta 7 días si el cliente entra por un anuncio CTWA y se responde en la ventana. Es la palanca más grande si hay inversión en ads, pero depende del canal de adquisición; no aplica a mensajes orgánicos.

## 8. Presupuesto y alertas recomendados

| Métrica | Alerta | Acción |
|---|---|---|
| Service del mes / 1.000 | 80% | Revisar duplicados y consolidar mensajes. |
| Costo Meta del mes / presupuesto | 70% / 90% / 100% | Pausar campañas marketing, revisar categorías, alertar al owner. |
| Marketing / total | >30% sin retorno medido | Pausar y revisar segmentación/opt-in. |
| Mensajes fallidos | >2% | Revisar calidad, número, plantillas y ventana. |
| Duplicados por pedido | >1,05 | Corregir idempotencia/reintentos. |
| Costo por pedido | Sube sin subir pedidos | Revisar mensajes por pedido y categorías. |

**Presupuesto base (solo Meta, Colombia, sin FEP, sin marketing):**

- 10.000 pedidos × 2 service = **USD 15,20/mes** (COP 55.964,5).
- 10.000 pedidos × 3 service = **USD 23,20/mes** (COP 85.419,5).
- Agregar 1.000 marketing = **+USD 12,50** (COP 46.022,7).
- Agregar 2.000 utility = **+USD 1,60** (COP 5.891,0).

El presupuesto de IA/STT debe ir separado y con su propia alerta. No mezclar “costo WhatsApp” con “costo IA” en un solo número sin desglose.

## 9. Errores comunes que deben evitarse

1. **“Los emojis abaratan el mensaje.”** Falso. El precio es por mensaje/categoría/país; los emojis no cambian la tarifa.
2. **“Si acorto el texto, cuesta menos.”** Falso para Meta; el texto puede afectar tokens de IA, no el precio por mensaje.
3. **“Service es gratis.”** Desde el 2026-10-01 solo hay 1.000 gratis/mes por número; después se cobra.
4. **“Utility dentro de la ventana es gratis.”** Ya no; desde el 2026-10-01 se cobra.
5. **“Marketing es solo un poco más caro.”** En Colombia es 15,6 veces la tarifa service.
6. **“Un 200 de Meta significa entregado.”** No; significa aceptado. El costo se causa al entregar.
7. **“El reintento no cuesta.”** Si el primero se entregó y el segundo también, se cobran ambos.
8. **“FEP aplica a todos.”** Solo a entradas desde anuncios CTWA en app Android/iOS y con respuesta en la ventana.
9. **“Los volume tiers bajan todo.”** Solo utility/authentication.
10. **“La IA es gratis.”** No; se mide aparte y puede ser el costo dominante.

## 10. Plan de instrumentación mínimo

1. Parsear la respuesta de Meta al enviar y guardar `wamid` (hoy se descarta el cuerpo).
2. Procesar `statuses` y guardar `pricing` (billable, type, category, model).
3. Crear tabla `whatsapp_message` con dirección, categoría, plantilla, costo estimado, estado y vínculo a pedido/cliente.
4. Calcular el costo del mes por número/categoría y comparar con Billing.
5. Dashboard: service gratis consumido, marketing, utility, FEP, fallidos, duplicados, costo/pedido.
6. Alertas de presupuesto y de calidad (bloqueos/reportes).
7. Exportar un cierre mensual para contabilidad, con supuestos y conciliación.

## 11. Limitaciones

- No se accedió a la facturación real de Meta; no se validó la cuenta, moneda ni impuestos.
- No hay tráfico de producción ni ledger de mensajes; los escenarios son hipótesis.
- No se probaron plantillas ni ventanas reales; no se validó la categoría asignada por Meta.
- No se midió el costo de IA/STT; no se incluye.
- Las tarifas pueden cambiar en enero/abril/julio/octubre; repetir la consulta antes de presupuestar.
- Los valores en COP son la tarjeta oficial; la conversión facturada puede variar.
- El impacto de FEP/volume tiers depende del canal de adquisición y del volumen; no se asumió en los escenarios base.

**Conclusión:** en Colombia el costo transaccional de WhatsApp es bajo por mensaje, pero el sistema actual no lo mide y puede duplicarlo o dispararlo con marketing. La prioridad es **instrumentar, evitar duplicados y separar categorías**; los emojis y la longitud del texto no son palancas de ahorro. El plan de corrección debe incluir el ledger de mensajes y la conciliación con Meta antes de prometer un presupuesto.
