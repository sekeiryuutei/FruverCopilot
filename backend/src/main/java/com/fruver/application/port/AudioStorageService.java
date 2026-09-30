package com.fruver.application.port;

public interface AudioStorageService {
    /** Guarda el archivo y devuelve una llave opaca (storage_key). */
    String save(byte[] data, String mimeType);

    byte[] load(String storageKey);
}
