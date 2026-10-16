package com.fruver.infrastructure.ai;

import com.fruver.infrastructure.audio.GeminiSpeechToText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeminiAdaptersTest {

    @Test
    void stripMarkdownFencesRemovesJsonAndPlainFences() {
        assertEquals("{\"items\":[]}", GeminiOrderExtractor.stripMarkdownFences("```json\n{\"items\":[]}\n```"));
        assertEquals("{\"items\":[]}", GeminiOrderExtractor.stripMarkdownFences("```\n{\"items\":[]}\n```"));
        assertEquals("{\"items\":[]}", GeminiOrderExtractor.stripMarkdownFences("{\"items\":[]}"));
    }

    @Test
    void sanitizeMimeHandlesCodecsAndExtensions() {
        // En notas de voz de WhatsApp el Content-Type suele traer codecs=opus
        assertEquals("audio/ogg", GeminiSpeechToText.sanitizeMime("audio/ogg; codecs=opus"));
        assertEquals("audio/mp4", GeminiSpeechToText.sanitizeMime("audio/mp4; codecs=mp4a"));
        assertEquals("audio/wav", GeminiSpeechToText.sanitizeMime("audio/wav"));
        assertEquals("audio/mp3", GeminiSpeechToText.sanitizeMime("audio/mpeg"));
        assertEquals("audio/ogg", GeminiSpeechToText.sanitizeMime(null));
        assertEquals("audio/ogg", GeminiSpeechToText.sanitizeMime(""));
    }
}

