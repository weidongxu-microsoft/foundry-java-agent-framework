package demo.photoprocess.withoutframework;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Pure-JDK JPEG crop — no third-party image library. IDENTICAL to the with-framework project's
 * {@code PhotoProcessor}: it is the shared "real work", not the thing being compared. The difference
 * between the two projects is entirely in the hosted-agent protocol code around it.
 */
public final class PhotoProcessor {

    /** A crop rectangle in source-image pixels, plus the model's one-line rationale. */
    public record CropBox(int x, int y, int width, int height, String reason) {
    }

    /** Decoded image dimensions, used to tell the model the pixel space it must crop within. */
    public record Dimensions(int width, int height) {
    }

    private PhotoProcessor() {
    }

    /** @return the pixel dimensions of a JPEG (so the crop prompt can reference the real size). */
    public static Dimensions dimensions(byte[] jpeg) throws IOException {
        BufferedImage image = decode(jpeg);
        return new Dimensions(image.getWidth(), image.getHeight());
    }

    /** Crops {@code jpeg} to {@code box} (clamped to bounds) and re-encodes the result as JPEG. */
    public static byte[] crop(byte[] jpeg, CropBox box) throws IOException {
        BufferedImage image = decode(jpeg);
        int imgW = image.getWidth();
        int imgH = image.getHeight();

        int x = clamp(box.x(), 0, imgW - 1);
        int y = clamp(box.y(), 0, imgH - 1);
        int w = clamp(box.width(), 1, imgW - x);
        int h = clamp(box.height(), 1, imgH - y);

        BufferedImage sub = image.getSubimage(x, y, w, h);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(sub, 0, 0, null);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "jpg", buffer)) {
            throw new IOException("No JPEG writer available");
        }
        return buffer.toByteArray();
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (image == null) {
            throw new IOException("Input is not a readable image");
        }
        return image;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
