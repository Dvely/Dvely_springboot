package com.example.dvely.deviceshare.application.service;

import com.example.dvely.deviceshare.application.session.PinImages;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 짚은 곳마다 테두리와 번호(①②…)를 그린 화면(설계 §15.10). 에이전트가 프롬프트의 번호와 화면의 위치를 맞춰 본다.
 *
 * <p>번호는 글꼴 대신 5×7 점 숫자로 그린다. 헤드리스 서버에 글꼴이 없을 수 있다.</p>
 */
final class AnnotatedScreen {

    static final int MAX_BYTES = 1024 * 1024;
    private static final Color MARK = new Color(0xC0, 0x26, 0xD3);
    private static final Color HALO = new Color(255, 255, 255, 230);
    /** 5×7 점 숫자. 줄마다 왼쪽 비트가 왼쪽 점이다 */
    private static final int[][] DIGITS = {
        {},
        {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110},
        {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111},
        {0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110},
        {0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010},
        {0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110},
    };

    /**
     * 짚은 곳 하나. 테두리가 없으면(빈 곳을 탭했다) 점에 고리를 그린다.
     *
     * @param n      번호(1~5)
     * @param bounds 고른 범위의 테두리(화면 비율). 없으면 null
     * @param point  짚은 점(화면 비율). 없으면 null
     */
    record Mark(int n, JsonNode bounds, JsonNode point) {
    }

    private AnnotatedScreen() {
    }

    /** @return 그린 JPEG. 화면을 읽지 못하면 null */
    static byte[] draw(byte[] screenJpeg, List<Mark> marks) {
        BufferedImage screen = PinImages.read(screenJpeg);
        if (screen == null || marks.isEmpty()) {
            return null;
        }
        int width = screen.getWidth();
        int height = screen.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(screen, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int shorter = Math.min(width, height);
            float stroke = Math.max(2f, shorter / 160f);
            int badge = (int) Math.max(22, shorter / 14.0);
            for (Mark mark : marks) {
                int[] box = box(mark.bounds(), width, height);
                int cx;
                int cy;
                if (box != null) {
                    // 화면 전체를 고른 곳은 선이 그림 밖으로 나가지 않게 안쪽으로 들인다
                    int inset = (int) Math.ceil(stroke);
                    int x = Math.max(inset, box[0]);
                    int y = Math.max(inset, box[1]);
                    int w = Math.min(width - inset, box[0] + box[2]) - x;
                    int h = Math.min(height - inset, box[1] + box[3]) - y;
                    outline(g, stroke, () -> g.drawRect(x, y, Math.max(1, w), Math.max(1, h)));
                    cx = x;
                    cy = y;
                } else if (mark.point() != null && mark.point().path("x").isNumber()) {
                    int px = (int) Math.round(clamp01(mark.point().path("x").asDouble()) * width);
                    int py = (int) Math.round(clamp01(mark.point().path("y").asDouble()) * height);
                    int r = badge / 2;
                    outline(g, stroke, () -> g.drawOval(px - r, py - r, r * 2, r * 2));
                    cx = px + r;
                    cy = py - r;
                } else {
                    continue;
                }
                badge(g, mark.n(), clamp(cx, badge / 2, width - badge / 2), clamp(cy, badge / 2, height - badge / 2), badge);
            }
        } finally {
            g.dispose();
        }
        byte[] jpeg = PinImages.encode(out, 0.85f);
        return jpeg == null ? null : PinImages.shrink(jpeg, MAX_BYTES);
    }

    /** 흰 테두리를 먼저 그리고 그 위에 색 선을 그린다. 어떤 배경에서도 보이게 */
    private static void outline(Graphics2D g, float stroke, Runnable shape) {
        g.setStroke(new BasicStroke(stroke * 2.2f));
        g.setColor(HALO);
        shape.run();
        g.setStroke(new BasicStroke(stroke));
        g.setColor(MARK);
        shape.run();
    }

    private static void badge(Graphics2D g, int n, int cx, int cy, int size) {
        int r = size / 2;
        g.setColor(HALO);
        g.fillOval(cx - r - 2, cy - r - 2, size + 4, size + 4);
        g.setColor(MARK);
        g.fillOval(cx - r, cy - r, size, size);
        if (n < 1 || n >= DIGITS.length) {
            return;
        }
        // 점 하나는 지름의 1/11. 숫자는 5×7 칸이라 가운데에 두면 위아래·양옆에 여백이 남는다
        double cell = size / 11.0;
        double left = cx - cell * 2.5;
        double top = cy - cell * 3.5;
        g.setColor(Color.WHITE);
        int[] rows = DIGITS[n];
        for (int row = 0; row < rows.length; row++) {
            for (int col = 0; col < 5; col++) {
                if ((rows[row] & (1 << (4 - col))) != 0) {
                    g.fillRect((int) Math.floor(left + col * cell), (int) Math.floor(top + row * cell),
                            (int) Math.ceil(cell), (int) Math.ceil(cell));
                }
            }
        }
    }

    /** @return 픽셀 {@code [x, y, w, h]}. 테두리가 없거나 틀리면 null */
    private static int[] box(JsonNode bounds, int width, int height) {
        if (bounds == null || !bounds.path("w").isNumber() || !bounds.path("h").isNumber()) {
            return null;
        }
        double x = clamp01(bounds.path("x").asDouble());
        double y = clamp01(bounds.path("y").asDouble());
        double w = Math.min(clamp01(bounds.path("w").asDouble()), 1 - x);
        double h = Math.min(clamp01(bounds.path("h").asDouble()), 1 - y);
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new int[] {(int) Math.floor(x * width), (int) Math.floor(y * height),
                (int) Math.ceil(w * width), (int) Math.ceil(h * height)};
    }

    private static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
