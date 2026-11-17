package com.example.dvely.deviceshare.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;
import javax.imageio.ImageIO;

/** 짚기 테스트용 JPEG. 글꼴 없이 도형만 그린다. */
public final class PinTestImages {

    private PinTestImages() {
    }

    /**
     * 긴 페이지(2000px)를 {@code scroll} 만큼 내려 본 390×844 화면. 같은 {@code seed} 면 같은 페이지다.
     */
    public static byte[] screen(int scroll, int seed) {
        BufferedImage image = new BufferedImage(390, 844, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Random random = new Random(seed);
            for (int top = 0; top < 2000; top += 48) {
                int shade = 40 + random.nextInt(200);
                int block = 30 + random.nextInt(300);
                int blockShade = random.nextInt(255);
                int y = top - scroll;
                g.setColor(new Color(shade, shade, shade));
                g.fillRect(0, y, 390, 48);
                g.setColor(new Color(blockShade, blockShade / 2, 255 - blockShade));
                g.fillRect(random.nextInt(60), y + 8, block, 30);
            }
        } finally {
            g.dispose();
        }
        return jpeg(image, "jpg");
    }

    /**
     * 가로 띠만 있는 화면(머리글·카드·목록 줄처럼 폭 전체를 채운 띠). {@code scroll} 만큼 내려 본다. 가로 이웃만 비교하는 지문은
     * 이런 화면의 스크롤을 못 알아본다.
     */
    public static byte[] bands(int scroll, int seed) {
        BufferedImage image = new BufferedImage(390, 844, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            Random random = new Random(seed);
            for (int top = 0; top < 2400; top += 60) {
                int shade = 60 + random.nextInt(190);
                g.setColor(new Color(shade, shade, Math.min(255, shade + 10)));
                g.fillRect(0, top - scroll, 390, 60);
            }
        } finally {
            g.dispose();
        }
        return jpeg(image, "jpg");
    }

    /** 잡음 그림. 압축이 잘 안 돼 크다 */
    public static byte[] noise(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xffffff));
            }
        }
        return jpeg(image, "jpg");
    }

    private static byte[] jpeg(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
