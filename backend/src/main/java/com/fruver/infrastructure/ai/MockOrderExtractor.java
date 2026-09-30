package com.fruver.infrastructure.ai;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.ExtractionUnavailableException;
import com.fruver.application.port.OrderExtractor;
import com.fruver.domain.enums.ConversationAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Simulador 100 % offline (AI_MODEL=mock). No usa Spring AI ni red: devuelve respuestas fijas para poder
 * probar toda la cadena (validacion, semaforo, pantalla, aprobacion, impresion) sin OpenAI.
 *
 * Comportamiento (segun el texto del mensaje):
 *   - contiene "simular error"   -> lanza ExtractionUnavailableException (prueba el camino rojo "IA NO DISPONIBLE")
 *   - contiene "simular ambiguo" -> "5 de papa" ambiguo + pregunta al cliente (prueba el ciclo de aclaracion)
 *   - es respuesta a un pedido en espera -> papa criolla 5 LB (cierra el ciclo de aclaracion)
 *   - cualquier otro mensaje     -> papa criolla 2 LB + tomate chonto 3 LB
 * NO es un modelo: no lee el pedido real. El sistema se niega a arrancar con mock en produccion.
 */
@Service
@ConditionalOnProperty(name = "spring.ai.openai.chat.options.model", havingValue = "mock")
public class MockOrderExtractor implements OrderExtractor {

    private static final Logger log = LoggerFactory.getLogger(MockOrderExtractor.class);

    public MockOrderExtractor() {
        log.warn("*** AI_MODEL=mock: la IA esta SIMULADA. Los pedidos NO se interpretan de verdad. ***");
    }

    @Override
    public AIOrderResponse extract(ExtractionRequest request) {
        String msg = request.message() == null ? "" : request.message().toLowerCase();

        if (msg.contains("simular error")) {
            throw new ExtractionUnavailableException("Error simulado por el modo mock", null);
        }
        if (msg.contains("simular ambiguo")) {
            return new AIOrderResponse(
                    List.of(new ItemSugeridoDTO("papa", null, new BigDecimal("5"), "LB", true, "No se especificó el tipo de papa.")),
                    ConversationAction.ASK_CUSTOMER,
                    "¿Qué tipo de papa necesitas: criolla, pastusa o sabanera?",
                    "Existen múltiples productos compatibles.");
        }
        if (request.currentItems() != null && !request.currentItems().isEmpty()) {
            return new AIOrderResponse(
                    List.of(new ItemSugeridoDTO("papa criolla", "Papa criolla", new BigDecimal("5"), "LB", false, null)),
                    ConversationAction.CONFIRM_ORDER, null, null);
        }
        return new AIOrderResponse(
                List.of(new ItemSugeridoDTO("papa criolla", "Papa criolla", new BigDecimal("2"), "LB", false, null),
                        new ItemSugeridoDTO("tomate chonto", "Tomate chonto", new BigDecimal("3"), "LB", false, null)),
                ConversationAction.CONFIRM_ORDER, null, null);
    }
}
