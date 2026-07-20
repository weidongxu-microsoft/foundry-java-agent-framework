package io.github.weidongxu.photo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageCropTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullFrameOrDegenerateRectIsNotMeaningful() throws Exception {
        assertNull(CropRect.fromJsonNode(null));
        assertNull(CropRect.fromJsonNode(
                mapper.readTree("{\"left\":0,\"top\":0,\"right\":1,\"bottom\":1}")));
        // keeps too little of a dimension → rejected
        assertNull(CropRect.fromJsonNode(
                mapper.readTree("{\"left\":0.1,\"top\":0.1,\"right\":0.3,\"bottom\":0.9}")));
    }

    @Test
    void meaningfulRectParses() throws Exception {
        CropRect rect = CropRect.fromJsonNode(
                mapper.readTree("{\"left\":0.1,\"top\":0.05,\"right\":0.9,\"bottom\":0.95}"));
        assertNotNull(rect);
        assertEquals(0.8, rect.getWidth(), 1e-9);
        assertEquals(0.9, rect.getHeight(), 1e-9);
    }

    @Test
    void cropTrimsTheJpegToTheNormalizedRect() throws IOException {
        byte[] jpeg = solidJpeg(1000, 800);
        CropRect rect = new CropRect(0.1, 0.2, 0.6, 0.7); // 50% x 50%

        byte[] out = ImageCrop.crop(jpeg, rect, 90);

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
        assertEquals(500, img.getWidth());
        assertEquals(400, img.getHeight());
    }

    @Test
    void nullOrFullRectReturnsOriginalBytes() throws IOException {
        byte[] jpeg = solidJpeg(200, 200);
        assertSame(jpeg, ImageCrop.crop(jpeg, null, 90));
        assertSame(jpeg, ImageCrop.crop(jpeg, new CropRect(0, 0, 1, 1), 90));
    }

    private static byte[] solidJpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(120, 60, 30));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpeg", out));
        return out.toByteArray();
    }
}
