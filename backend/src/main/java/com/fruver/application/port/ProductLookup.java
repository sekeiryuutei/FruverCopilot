package com.fruver.application.port;

import com.fruver.application.dto.ItemSugeridoDTO;

/** Resuelve una propuesta de la IA contra el catalogo real. La IA nunca crea productos. */
public interface ProductLookup {

    /** matches: 0 = desconocido, 1 = unico, >1 = varios compatibles. productId solo si matches == 1. */
    record Resolution(Long productId, int matches, String unit) {
        public Resolution(Long productId, int matches) {
            this(productId, matches, null);
        }
    }

    Resolution resolve(ItemSugeridoDTO item);
}
