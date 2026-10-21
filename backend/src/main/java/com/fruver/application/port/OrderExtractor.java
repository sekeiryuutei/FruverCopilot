package com.fruver.application.port;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;

public interface OrderExtractor {
    /** @throws ExtractionUnavailableException error tecnico del proveedor (no del pedido). */
    AIOrderResponse extract(ExtractionRequest request);

    default AIOrderResponse extractImage(byte[] imageBytes, String mimeType, ExtractionRequest request) {
        return extract(request);
    }
}
