package com.fruver.application.port;

public interface SpeechToTextService {
    /** @throws RuntimeException si el proveedor falla. Devuelve texto (posiblemente vacio). */
    String transcribe(byte[] audio, String mimeType);
}
