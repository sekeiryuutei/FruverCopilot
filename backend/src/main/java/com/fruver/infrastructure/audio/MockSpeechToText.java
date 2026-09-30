package com.fruver.infrastructure.audio;

import com.fruver.application.port.SpeechToTextService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Con AI_MODEL=mock tampoco se llama a OpenAI para transcribir: devuelve una frase fija (offline). */
@Service
@ConditionalOnProperty(name = "spring.ai.openai.chat.options.model", havingValue = "mock")
public class MockSpeechToText implements SpeechToTextService {

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        return "dos libras de papa criolla y tres libras de tomate chonto";
    }
}
