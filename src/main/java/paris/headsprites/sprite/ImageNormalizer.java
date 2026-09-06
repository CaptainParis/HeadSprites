package paris.headsprites.sprite;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

public final class ImageNormalizer {
    private static final int SKIN_W = 64;
    private static final int SKIN_H = 64;
    private static final int FACE = 8;
    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIMENSION = 2048;

    static {
        ImageIO.setUseCache(false);
    }

    private ImageNormalizer() {
    }

    public static String normalizeToSkinDataUrl(String input) throws IOException {
        BufferedImage source = decode(input);
        BufferedImage face = scaleNearest(source, FACE, FACE);

        BufferedImage skin = new BufferedImage(SKIN_W, SKIN_H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < FACE; y++) {
            for (int x = 0; x < FACE; x++) {
                skin.setRGB(FACE_X + x, FACE_Y + y, face.getRGB(x, y));
            }
        }
        return encode(skin);
    }

    public static BufferedImage decode(String input) throws IOException {
        if (input == null || input.isBlank()) {
            throw new IOException("Empty image data.");
        }
        String base64 = input;
        int comma = input.indexOf(',');
        if (input.startsWith("data:") && comma >= 0) {
            base64 = input.substring(comma + 1);
        }
        if (base64.length() > (long) MAX_BYTES * 4 / 3 + 4) {
            throw new IOException("Image too large (max 4 MiB).");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid base64 image data.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Image too large (max 4 MiB).");
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) {
                throw new IOException("Unsupported or corrupt image data.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("Image has no pixels.");
                }
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new IOException("Image is " + width + "x" + height
                            + "; the limit is " + MAX_DIMENSION + "px per side.");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("Unsupported or corrupt image data.");
                }
                return image;
            } catch (RuntimeException e) {
                throw new IOException("Corrupt image data: " + e.getMessage());
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scaleNearest(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int sw = src.getWidth();
        int sh = src.getHeight();
        for (int y = 0; y < h; y++) {
            int sy = Math.min(sh - 1, y * sh / h);
            for (int x = 0; x < w; x++) {
                int sx = Math.min(sw - 1, x * sw / w);
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
    }

    private static String encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG encoder available.");
        }
        String base64 = Base64.getEncoder().encodeToString(out.toByteArray());
        return "data:image/png;base64," + base64;
    }
}
