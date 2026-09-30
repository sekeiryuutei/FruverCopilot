package com.fruver.infrastructure.audio;

import com.fruver.application.port.AudioStorageService;
import com.fruver.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/** Almacenamiento en disco (volumen Docker). Para MinIO/S3 basta otra implementacion de AudioStorageService. */
@Service
@ConditionalOnProperty(name = "app.audio.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalAudioStorage implements AudioStorageService {

    private static final Pattern SAFE_KEY = Pattern.compile("[a-f0-9-]{36}\\.[a-z0-9]{2,5}");
    private final Path root;

    public LocalAudioStorage(AppProperties props) {
        this.root = Path.of(props.audio().storagePath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio de audio", e);
        }
    }

    @Override
    public String save(byte[] data, String mimeType) {
        String key = UUID.randomUUID() + "." + extension(mimeType);
        try {
            Files.write(root.resolve(key), data);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo guardar el audio", e);
        }
        return key;
    }

    @Override
    public byte[] load(String storageKey) {
        if (storageKey == null || !SAFE_KEY.matcher(storageKey).matches()) { // evita path traversal
            throw new IllegalArgumentException("Llave de audio invalida");
        }
        try {
            return Files.readAllBytes(root.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el audio", e);
        }
    }

    public static String extension(String mime) {
        String m = mime == null ? "" : mime.toLowerCase();
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3";
        if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return "m4a";
        if (m.contains("wav")) return "wav";
        if (m.contains("webm")) return "webm";
        if (m.contains("amr")) return "amr";
        return "ogg";
    }
}
