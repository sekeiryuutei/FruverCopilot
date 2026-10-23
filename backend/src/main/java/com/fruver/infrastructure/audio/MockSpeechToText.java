package com.fruver.infrastructure.audio;

import com.fruver.application.port.SpeechToTextService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/** Con AI_MODEL=mock o sin clave API tampoco se llama a proveedores externos para transcribir (offline). */
@Service
@ConditionalOnExpression("'${app.ai.provider:gemini}'.equalsIgnoreCase('mock') || '${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock') || ('${app.ai.provider:gemini}'.equalsIgnoreCase('gemini') && ('${app.ai.gemini.api-key:}'.trim().length() <= 8 || '${app.ai.gemini.api-key:}'.trim().startsWith('#')) && ('${app.ai.gemini.api-keys:}'.trim().length() <= 8 || '${app.ai.gemini.api-keys:}'.trim().startsWith('#')))")
public class MockSpeechToText implements SpeechToTextService {

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        return "dos libras de papa criolla y tres libras de tomate chonto";
    }
}
