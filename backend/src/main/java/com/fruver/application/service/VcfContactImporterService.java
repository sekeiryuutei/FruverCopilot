package com.fruver.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Importa de manera segura y no destructiva los contactos del archivo VCF (backups/contactos paisa.vcf)
 * garantizando la prioridad absoluta del nombre del cliente en el sistema.
 */
@Service
public class VcfContactImporterService {

    private static final Logger log = LoggerFactory.getLogger(VcfContactImporterService.class);
    private final JdbcClient jdbc;
    private final AtomicBoolean imported = new AtomicBoolean(false);

    public VcfContactImporterService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void importVcfContacts() {
        if (!imported.compareAndSet(false, true)) return;

        File vcfFile = new File("backups/contactos paisa.vcf");
        if (!vcfFile.exists()) {
            vcfFile = new File("../backups/contactos paisa.vcf");
        }
        if (!vcfFile.exists()) {
            log.debug("Archivo de contactos VCF no encontrado en las rutas estándar. Omitiendo importación.");
            return;
        }

        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(vcfFile, StandardCharsets.UTF_8))) {
            String line;
            String currentFn = null;
            String currentTel = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.equalsIgnoreCase("BEGIN:VCARD")) {
                    currentFn = null;
                    currentTel = null;
                } else if (line.startsWith("FN:") || line.startsWith("fn:")) {
                    currentFn = line.substring(3).trim();
                } else if (line.toUpperCase().startsWith("TEL;") || line.toUpperCase().startsWith("TEL:")) {
                    int colonIdx = line.indexOf(':');
                    if (colonIdx >= 0 && colonIdx < line.length() - 1) {
                        String rawTel = line.substring(colonIdx + 1).trim();
                        if (currentTel == null && !rawTel.isBlank()) {
                            currentTel = rawTel;
                        }
                    }
                } else if (line.equalsIgnoreCase("END:VCARD")) {
                    if (currentFn != null && !currentFn.isBlank() && currentTel != null && !currentTel.isBlank()) {
                        String normalizedPhone = Phones.normalize(currentTel);
                        if (normalizedPhone != null && normalizedPhone.length() >= 10) {
                            upsertContact(currentFn, normalizedPhone);
                            count++;
                        }
                    }
                    currentFn = null;
                    currentTel = null;
                }
            }
            log.info("Sincronización de contactos VCF completada: {} contactos verificados y protegidos.", count);
        } catch (Exception e) {
            log.warn("Error leyendo archivo de contactos VCF: {}", e.getMessage());
        }
    }

    private void upsertContact(String name, String phone) {
        try {
            jdbc.sql("""
                    INSERT INTO customer(name, phone, name_source, customer_type)
                    VALUES (:n, :p, 'IMPORTED_CONTACT', 'RETAIL')
                    ON CONFLICT (phone) DO UPDATE SET
                        name = CASE
                            WHEN customer.name_source = 'MANUAL' THEN customer.name
                            ELSE EXCLUDED.name
                        END,
                        name_source = CASE
                            WHEN customer.name_source = 'MANUAL' THEN customer.name_source
                            ELSE 'IMPORTED_CONTACT'
                        END
                    """).param("n", name).param("p", phone).update();
        } catch (Exception ex) {
            // Ignorar errores individuales para no interrumpir el lote
            log.trace("No se pudo insertar contacto {}: {}", Phones.mask(phone), ex.getMessage());
        }
    }
}

