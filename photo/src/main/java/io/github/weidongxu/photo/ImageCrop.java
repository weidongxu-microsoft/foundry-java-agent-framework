package io.github.weidongxu.photo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Crops an already-encoded JPEG by a normalized {@link CropRect}, re-encoding the result. The crop
 * is applied to the developed JPEG (not the RAW): it needs no knowledge of the sensor dimensions and
 * is exact against the JPEG's actual pixels. A tighter crop naturally yields a smaller image.
 */
public final class ImageCrop {

    private ImageCrop() {
    }

    /**
     * Crops {@code jpeg} to {@code rect} and re-encodes at {@code jpegQuality} (1..100). Returns the
     * original bytes unchanged when {@code rect} is {@code null}/not {@link CropRect#isMeaningful()
     * meaningful} or the resulting rectangle would be empty.
     *
     * @throws RawDevelopException if the bytes cannot be decoded or re-encoded as JPEG.
     */
    public static byte[] crop(byte[] jpeg, CropRect rect, int jpegQuality) {
        if (rect == null || !rect.isMeaningful()) {
            return jpeg;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (image == null) {
                throw new RawDevelopException("Could not decode JPEG for cropping");
            }
            int w = image.getWidth();
            int h = image.getHeight();
            int x = (int) Math.round(rect.getLeft() * w);
            int y = (int) Math.round(rect.getTop() * h);
            int cw = (int) Math.round(rect.getWidth() * w);
            int ch = (int) Math.round(rect.getHeight() * h);
            x = Math.max(0, Math.min(x, w - 1));
            y = Math.max(0, Math.min(y, h - 1));
            cw = Math.max(1, Math.min(cw, w - x));
            ch = Math.max(1, Math.min(ch, h - y));
            if (cw >= w && ch >= h) {
                return jpeg;
            }
            BufferedImage cropped = image.getSubimage(x, y, cw, ch);
            return encodeJpeg(cropped, jpegQuality);
        } catch (IOException e) {
            throw new RawDevelopException("Failed to crop JPEG", e);
        }
    }

    private static byte[] encodeJpeg(BufferedImage image, int jpegQuality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new RawDevelopException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(Math.max(1, Math.min(jpegQuality, 100)) / 100f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
