package com.fruver.infrastructure.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Optimizador de imágenes para extracción con IA.
 * Si la foto de WhatsApp supera los 768px de ancho o alto, la redimensiona manteniendo el aspect ratio.
 * Reduce el consumo de tokens en ~75%, ahorra ancho de banda y acelera la respuesta del modelo.
 */
public final class ImageOptimizer {

    private static final Logger log = LoggerFactory.getLogger(ImageOptimizer.class);
    public static final int DEFAULT_MAX_PX = 768;

    private ImageOptimizer() {}

    public static byte[] optimizeForAi(byte[] imageBytes) {
        return optimizeForAi(imageBytes, DEFAULT_MAX_PX);
    }

    public static byte[] optimizeForAi(byte[] imageBytes, int maxPx) {
        if (imageBytes == null || imageBytes.length == 0) {
            return imageBytes;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (src == null) {
                return imageBytes;
            }
            int w = src.getWidth();
            int h = src.getHeight();

            // Si ya es menor o igual al límite, se usa directamente
            if (w <= maxPx && h <= maxPx) {
                return imageBytes;
            }

            double scale = Math.min((double) maxPx / w, (double) maxPx / h);
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));

            BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean written = ImageIO.write(dst, "jpg", baos);
            if (!written) {
                return imageBytes;
            }

            byte[] resized = baos.toByteArray();
            log.info("Imagen optimizada para IA: {}x{} ({} KB) -> {}x{} ({} KB)",
                    w, h, imageBytes.length / 1024, nw, nh, resized.length / 1024);
            return resized;
        } catch (Exception e) {
            log.warn("No se pudo redimensionar la imagen para IA (se enviará original): {}", e.getMessage());
            return imageBytes;
        }
    }
}
