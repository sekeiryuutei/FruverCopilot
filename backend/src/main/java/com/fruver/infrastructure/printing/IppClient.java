package com.fruver.infrastructure.printing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cliente nativo IPP (Internet Printing Protocol RFC 8011) para impresoras modernas
 * Brother, AirPrint y Mopria que aceptan image/pwg-raster por el puerto 631.
 */
public final class IppClient {

    private static final Logger log = LoggerFactory.getLogger(IppClient.class);

    private IppClient() {}

    public static void printRaster(String host, int port, String jobName, byte[] pwgRasterData) throws IOException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // IPP Version 2.0
            dos.writeShort(0x0200);
            // Operation: Print-Job (0x0002)
            dos.writeShort(0x0002);
            // Request ID
            dos.writeInt(1);

            // Operation Attributes Tag (0x01)
            dos.writeByte(0x01);

            writeAttribute(dos, 0x47, "attributes-charset", "utf-8");
            writeAttribute(dos, 0x48, "attributes-natural-language", "es");
            writeAttribute(dos, 0x45, "printer-uri", "ipp://" + host + ":" + port + "/ipp/print");
            writeAttribute(dos, 0x42, "requesting-user-name", "FruverCopilot");
            writeAttribute(dos, 0x42, "job-name", (jobName != null && !jobName.isBlank()) ? jobName : "Pedido_Fruver");
            writeAttribute(dos, 0x49, "document-format", "image/pwg-raster");

            // Job Attributes Tag (0x02)
            dos.writeByte(0x02);
            writeAttribute(dos, 0x44, "media", "iso_a5_148x210mm");

            // End of attributes tag (0x03)
            dos.writeByte(0x03);

            // Payload binario (datos PWG Raster)
            dos.write(pwgRasterData);
            dos.flush();

            byte[] requestBody = baos.toByteArray();

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .build();

            String url = "http://" + host + ":" + port + "/ipp/print";
            log.info("Enviando trabajo IPP ({}) a {} (tamaño: {} bytes)", jobName, url, pwgRasterData.length);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/ipp")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("La impresora respondió HTTP " + response.statusCode());
            }

            byte[] respBody = response.body();
            if (respBody != null && respBody.length >= 4) {
                int statusCode = ((respBody[2] & 0xFF) << 8) | (respBody[3] & 0xFF);
                // 0x0000 = successful-ok, 0x0001 = successful-ok-ignored-or-substituted-attributes
                if (statusCode > 0x00FF) {
                    throw new IOException("La impresora reportó error IPP (código 0x" + Integer.toHexString(statusCode) + ")");
                }
            }
            log.info("Trabajo IPP aceptado exitosamente por la impresora en {}:{}", host, port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Impresión IPP interrumpida", e);
        }
    }

    private static void writeAttribute(DataOutputStream dos, int tag, String name, String value) throws IOException {
        dos.writeByte(tag);
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        dos.writeShort(nameBytes.length);
        dos.write(nameBytes);
        byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
        dos.writeShort(valBytes.length);
        dos.write(valBytes);
    }
}

