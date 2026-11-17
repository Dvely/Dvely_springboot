package com.example.dvely.deviceshare.application.session;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.OptionalLong;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 짚기 이미지 도우미(설계 §15.5·§15.9). 글꼴을 쓰지 않아 헤드리스 서버에서도 돈다.
 *
 * <p>화면 지문(dHash)은 서버가 기기가 보낸 화면 JPEG 로 만든다. 설계는 기기가 만들게 했지만, 서버는 이미 그 JPEG 를 받고 ImageIO 가
 * 있어 CLI 를 고치지 않아도 되고 옛 CLI 에도 된다.</p>
 */
public final class PinImages {

    private static final float[] QUALITIES = {0.8f, 0.65f, 0.5f};
    private static final double[] SCALES = {1.0, 0.75, 0.5, 0.35};
    /** 이만큼(0~255)도 차이 나지 않는 이웃 칸은 같은 밝기로 본다 */
    private static final double FLAT = 2.0;

    private PinImages() {
    }

    /** @return 읽은 그림. 읽지 못하면 null */
    public static BufferedImage read(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 상한 안에 든 JPEG. 이미 작으면 그대로 돌려주고, 크면 품질을 낮추고 그래도 크면 줄인다.
     *
     * @return 줄이지 못하면(읽지 못하는 그림) null
     */
    public static byte[] shrink(byte[] jpeg, int maxBytes) {
        if (jpeg == null || jpeg.length <= maxBytes) {
            return jpeg;
        }
        BufferedImage image = read(jpeg);
        if (image == null) {
            return null;
        }
        for (double scale : SCALES) {
            BufferedImage scaled = scaled(image, scale);
            for (float quality : QUALITIES) {
                byte[] out = encode(scaled, quality);
                if (out != null && out.length <= maxBytes) {
                    return out;
                }
            }
        }
        return null;
    }

    /**
     * 64비트 dHash. 그림을 가로 8 · 세로 9 칸으로 나눠 칸마다 밝기 평균을 내고, 칸마다 아래 칸이 뚜렷이 밝으면 1 이다.
     *
     * <p>세로 이웃을 비교한다. 앱 화면은 가로 띠(머리글·카드·목록 줄)로 되어 있어, 가로 이웃 비교는 스크롤해도 거의 바뀌지
     * 않는다(실측: 예제 앱을 720px 내려도 8비트). 세로 비교는 같은 화면 0~1비트, 4px 1비트, 40px 이상 24비트 넘게 다르다.</p>
     */
    public static OptionalLong dhash(BufferedImage image) {
        if (image == null || image.getWidth() < 8 || image.getHeight() < 9) {
            return OptionalLong.empty();
        }
        int width = image.getWidth();
        int height = image.getHeight();
        // 칸마다 수십 점이면 된다. 큰 화면(1170×2532)도 2만 점 안쪽에서 끝난다
        int step = Math.max(1, Math.min(width, height) / 90);
        double[] sum = new double[8 * 9];
        int[] count = new int[8 * 9];
        for (int y = step / 2; y < height; y += step) {
            int row = Math.min(8, y * 9 / height);
            for (int x = step / 2; x < width; x += step) {
                int rgb = image.getRGB(x, y);
                double gray = 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                int cell = row * 8 + Math.min(7, x * 8 / width);
                sum[cell] += gray;
                count[cell]++;
            }
        }
        long hash = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int upper = row * 8 + col;
                int lower = upper + 8;
                double a = count[upper] == 0 ? 0 : sum[upper] / count[upper];
                double b = count[lower] == 0 ? 0 : sum[lower] / count[lower];
                // 빈 바탕(흰 화면)의 이웃 칸은 밝기가 같다. 그대로 비교하면 JPEG 잡음이 비트를 뒤집어, 같은 화면도 달라 보인다
                hash = (hash << 1) | (b - a > FLAT ? 1 : 0);
            }
        }
        return OptionalLong.of(hash);
    }

    /** @return JPEG 바이트. 쓰지 못하면 null */
    public static byte[] encode(BufferedImage image, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(rgb(image), null, null), param);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** JPEG 쓰기는 알파가 있으면 실패한다. RGB 로 옮긴다 */
    private static BufferedImage rgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        return scaled(image, 1.0);
    }

    private static BufferedImage scaled(BufferedImage image, double scale) {
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
