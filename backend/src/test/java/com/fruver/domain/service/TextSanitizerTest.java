package com.fruver.domain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextSanitizerTest {

    @Test
    @DisplayName("Debe remover emojis simples y complejos sin alterar caracteres en español")
    void testCleanTextRemovesEmojisPreservesSpanish() {
        String input = "Briyed Molina 🦋🧠 (Manzana Gala 🍎✨)";
        String cleaned = TextSanitizer.cleanText(input);
        assertEquals("Briyed Molina (Manzana Gala )", cleaned);

        // Español con tildes, eñes y caracteres monetarios
        String spanish = "Limón Tahití 100% fresco, Champiñón de París y Ñame espino $15.000";
        assertEquals(spanish, TextSanitizer.cleanText(spanish));
    }

    @Test
    @DisplayName("Debe remover secuencias compuestas de emojis (ZWJ, tonos de piel y banderas)")
    void testCleanComplexCompoundEmojis() {
        // 👧🏻 (niña con tono de piel), 👨‍👩‍👧 (familia ZWJ), 🇨🇴 (bandera regional)
        String input = "Gladis Possu 👧🏻 familia 👨‍👩‍👧 desde Colombia 🇨🇴";
        String cleaned = TextSanitizer.cleanText(input);
        assertEquals("Gladis Possu familia desde Colombia", cleaned);
    }

    @Test
    @DisplayName("Debe sanitizar nombre de cliente y truncar a máximo 40 caracteres")
    void testSanitizeCustomerName() {
        // Caso normal con emojis
        assertEquals("Briyed Molina", TextSanitizer.sanitizeCustomerName("Briyed Molina 🦋🧠"));

        // Caso vacío o nulo
        assertEquals("CLIENTE GENERAL", TextSanitizer.sanitizeCustomerName(null));
        assertEquals("CLIENTE GENERAL", TextSanitizer.sanitizeCustomerName("   "));
        assertEquals("CLIENTE GENERAL", TextSanitizer.sanitizeCustomerName("🦋🧠"));

        // Caso ultra largo (100 caracteres)
        String longName = "DON JUAN CARLOS DE LA SANTISIMA TRINIDAD RODRIGUEZ PEREZ Y ASOCIADOS S.A.S.";
        String sanitized = TextSanitizer.sanitizeCustomerName(longName);
        assertTrue(sanitized.length() <= 40, "Longitud no debe superar 40 caracteres");
        assertEquals(longName.substring(0, 40).trim(), sanitized);
    }

    @Test
    @DisplayName("Debe sanitizar direcciones y truncar a máximo 60 caracteres")
    void testSanitizeAddress() {
        String input = "Calle 10 # 4-50 🏡 Apto 201 Torre B 📍";
        assertEquals("Calle 10 # 4-50 Apto 201 Torre B", TextSanitizer.sanitizeAddress(input));

        String ultraLongAddr = "Carrera 45A Bis Norte Número 123-45 Interior 4 Casa 12 Conjunto Campestre Los Guayacanes Manzana F";
        String sanitized = TextSanitizer.sanitizeAddress(ultraLongAddr);
        assertTrue(sanitized.length() <= 60, "Longitud de dirección no debe superar 60 caracteres");
    }

    @Test
    @DisplayName("Debe sanitizar notas descartando meta-razonamientos de IA y truncando a 100 caracteres")
    void testSanitizeNotes() {
        String noteWithAi = "tomado de la lista manuscrita enviada por audio a las 10 am";
        assertEquals("", TextSanitizer.sanitizeNotes(noteWithAi));

        String noteKitchen = "Favor maduro para hoy 🚨📦 empacar separado 🙏🏻";
        assertEquals("Favor maduro para hoy empacar separado", TextSanitizer.sanitizeNotes(noteKitchen));

        String ultraLongNote = "Por favor seleccionar los aguacates más verdes posibles para el fin de semana porque es para un evento especial en el restaurante campestre de la sede sur";
        String sanitized = TextSanitizer.sanitizeNotes(ultraLongNote);
        assertTrue(sanitized.length() <= 100, "Longitud de notas no debe superar 100 caracteres");
    }

    @Test
    @DisplayName("Debe sanitizar nombres de productos descartando razonamientos y truncando a 45 caracteres")
    void testSanitizeProductName() {
        String input1 = "3 BULTOS (TOMADO DE LA LISTA MANUSCRITA / HISTORIAL FRECUENTE)";
        assertEquals("3 BULTOS", TextSanitizer.sanitizeProductName(input1));

        String input2 = "Cebolla Cabezona Roja (bien seca para bodega) 🧅✨";
        assertEquals("Cebolla Cabezona Roja (bien seca para bodega)", TextSanitizer.sanitizeProductName(input2));

        String ultraLongProduct = "Manzana Royal Gala Importada Extra Grande Calidad Superior Seleccionada Especialmente";
        String sanitized = TextSanitizer.sanitizeProductName(ultraLongProduct);
        assertTrue(sanitized.length() <= 45, "Longitud de producto no debe superar 45 caracteres");
    }

    @Test
    @DisplayName("Debe escapar HTML correctamente para prevenir inyecciones en el PDF")
    void testEscapeHtml() {
        assertEquals("&lt;b&gt;Manzana&lt;/b&gt; &amp; Pera", TextSanitizer.escapeHtml("<b>Manzana</b> & Pera"));
        assertEquals("&quot;Oferta&#39;s&quot;", TextSanitizer.escapeHtml("\"Oferta's\""));
    }
}

