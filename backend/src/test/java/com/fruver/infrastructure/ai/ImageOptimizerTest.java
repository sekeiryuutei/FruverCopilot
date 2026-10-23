package com.fruver.infrastructure.ai;

import com.fruver.infrastructure.media.ImageOptimizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ImageOptimizerTest {

    @Test
    void redimensionaImagenGrandeManteniendoAspecto() throws IOException {
        // Crear imagen sintética de 1600x1200
        BufferedImage big = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 1600, 1200);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(big, "jpg", baos);
        byte[] original = baos.toByteArray();

        // Optimizar a max 768px
        byte[] optimized = ImageOptimizer.optimizeForAi(original, 768);
        assertNotNull(optimized);
        assertTrue(optimized.length > 0);

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(optimized));
        assertEquals(768, result.getWidth());
        assertEquals(576, result.getHeight()); // 1200 * (768/1600) = 576
    }

    @Test
    void imagenPequenaNoSeModifica() throws IOException {
        BufferedImage small = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(small, "jpg", baos);
        byte[] original = baos.toByteArray();

        byte[] optimized = ImageOptimizer.optimizeForAi(original, 768);
        assertSame(original, optimized);
    }
}

