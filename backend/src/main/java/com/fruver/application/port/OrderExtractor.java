package com.fruver.application.port;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;

import java.util.List;

public interface OrderExtractor {
    /** @throws ExtractionUnavailableException error tecnico del proveedor (no del pedido). */
    AIOrderResponse extract(ExtractionRequest request);

    default AIOrderResponse extractImage(byte[] imageBytes, String mimeType, ExtractionRequest request) {
        return extract(request);
    }

    default AIOrderResponse extractBatch(List<byte[]> images, List<String> mimeTypes, ExtractionRequest request) {
        if (images != null && !images.isEmpty()) {
            return extractImage(images.get(0), (mimeTypes != null && !mimeTypes.isEmpty()) ? mimeTypes.get(0) : "image/jpeg", request);
        }
        return extract(request);
    }
}
