package paris.headsprites.sprite;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class ImageNormalizer {
    private static final int SKIN_W = 64;
    private static final int SKIN_H = 64;
    private static final int FACE = 8;
    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;

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
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid base64 image data.");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("Unsupported or corrupt image data.");
        }
        return image;
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
        ImageIO.write(image, "png", out);
        String base64 = Base64.getEncoder().encodeToString(out.toByteArray());
        return "data:image/png;base64," + base64;
    }
}
