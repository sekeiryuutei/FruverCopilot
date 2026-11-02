package com.fruver.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class MediaCleanupService {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanupService.class);

    private final JdbcClient jdbc;

    public MediaCleanupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        cleanOldImages();
    }

    /** Se ejecuta automáticamente todas las noches a las 3:00 AM */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanOldImages() {
        try {
            log.info("Iniciando purga automática de imágenes de pedidos mayores a 3 días...");

            List<String> storageKeys = jdbc.sql("""
                    SELECT storage_key FROM media_image
                    WHERE created_at < NOW() - INTERVAL '3 days'
                      AND storage_key IS NOT NULL AND storage_key <> ''
                    """).query(String.class).list();

            int filesDeleted = 0;
            for (String key : storageKeys) {
                try {
                    Path p = Paths.get(key);
                    if (Files.exists(p) && Files.isRegularFile(p)) {
                        Files.delete(p);
                        filesDeleted++;
                    }
                } catch (Exception e) {
                    log.debug("No se pudo eliminar archivo físico {}: {}", key, e.getMessage());
                }
            }

            int rowsDeleted = jdbc.sql("""
                    DELETE FROM media_image
                    WHERE created_at < NOW() - INTERVAL '3 days'
                    """).update();

            log.info("Purga de imágenes completada: {} archivos físicos borrados, {} registros eliminados de BD.",
                    filesDeleted, rowsDeleted);
        } catch (Exception e) {
            log.error("Error durante la limpieza programada de imágenes: {}", e.getMessage());
        }
    }
}
