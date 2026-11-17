package com.example.dvely.deviceshare.application.service;

import com.example.dvely.deviceshare.application.session.PinImages;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 사람이 고른 범위(§15.10)를 화면 JPEG 에서 잘라 낸다.
 *
 * <p>기기는 짚은 요소만 잘라 보낸다. 범위를 넓혀 폼·카드를 고르면 그 범위의 그림은 서버가 화면에서 만든다. 그래야 잘라 낸 그림만
 * 기본으로 붙이는 MCP(§14.5)도 사람이 고른 것을 본다. 글꼴을 쓰지 않아 헤드리스 서버에서도 돈다.</p>
 */
final class ScopeCrop {

    static final int MAX_SIDE = 800;
    static final int MAX_BYTES = 512 * 1024;
    /** 테두리 둘레 여백. 화면 폭에 대한 비율이고 8px 보다 작지 않다 */
    static final double MARGIN_RATIO = 0.015;

    private ScopeCrop() {
    }

    /** @return 잘라 낸 JPEG. 화면을 읽지 못했거나 테두리가 비었으면 null */
    static byte[] cut(byte[] screenJpeg, JsonNode bounds) {
        if (screenJpeg == null || bounds == null || !bounds.isObject()) {
            return null;
        }
        BufferedImage screen = PinImages.read(screenJpeg);
        if (screen == null) {
            return null;
        }
        int width = screen.getWidth();
        int height = screen.getHeight();
        int margin = (int) Math.max(8, Math.round(width * MARGIN_RATIO));
        int left = clamp((int) Math.floor(bounds.path("x").asDouble() * width) - margin, 0, width - 1);
        int top = clamp((int) Math.floor(bounds.path("y").asDouble() * height) - margin, 0, height - 1);
        int right = clamp((int) Math.ceil((bounds.path("x").asDouble() + bounds.path("w").asDouble()) * width) + margin, left + 1, width);
        int bottom = clamp((int) Math.ceil((bounds.path("y").asDouble() + bounds.path("h").asDouble()) * height) + margin, top + 1, height);
        BufferedImage area = screen.getSubimage(left, top, right - left, bottom - top);

        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(area.getWidth(), area.getHeight()));
        int outWidth = Math.max(1, (int) Math.round(area.getWidth() * scale));
        int outHeight = Math.max(1, (int) Math.round(area.getHeight() * scale));
        BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(area, 0, 0, outWidth, outHeight, null);
        } finally {
            g.dispose();
        }
        for (float quality : new float[] {0.8f, 0.65f, 0.5f}) {
            byte[] jpeg = PinImages.encode(out, quality);
            if (jpeg != null && jpeg.length <= MAX_BYTES) {
                return jpeg;
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
