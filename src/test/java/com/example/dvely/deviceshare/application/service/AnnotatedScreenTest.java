package com.example.dvely.deviceshare.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deviceshare.support.PinTestImages;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 짚은 곳마다 테두리와 번호를 그린 화면(설계 §15.10). 글꼴 없이 그린다. */
class AnnotatedScreenTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 짚은_곳마다_테두리와_번호_배지를_화면_크기_그대로_그린다() throws Exception {
        byte[] screen = PinTestImages.screen(0, 7);
        byte[] drawn = AnnotatedScreen.draw(screen, List.of(
                new AnnotatedScreen.Mark(1, json.readTree("{\"x\":0.1,\"y\":0.1,\"w\":0.5,\"h\":0.2}"), null),
                new AnnotatedScreen.Mark(2, null, json.readTree("{\"x\":0.5,\"y\":0.8}"))));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(drawn));
        assertThat(image.getWidth()).isEqualTo(390);
        assertThat(image.getHeight()).isEqualTo(844);
        // 테두리 왼쪽 변의 가운데(39, 253)는 보라색 선이다
        assertThat(magenta(image.getRGB(39, 253))).isTrue();
        // ① 배지는 테두리 왼쪽 위 모서리에 있다. 배지 안에는 흰 점 숫자가 있다
        int whites = 0;
        for (int y = 84 - 12; y < 84 + 12; y++) {
            for (int x = 39 - 12; x < 39 + 12; x++) {
                Color c = new Color(image.getRGB(Math.max(0, x), Math.max(0, y)));
                if (c.getRed() > 230 && c.getGreen() > 230 && c.getBlue() > 230) {
                    whites++;
                }
            }
        }
        assertThat(whites).as("점 숫자").isGreaterThan(5);
        assertThat(drawn.length).isLessThanOrEqualTo(AnnotatedScreen.MAX_BYTES);
    }

    @Test
    void 화면을_읽지_못하면_그리지_않는다() throws Exception {
        assertThat(AnnotatedScreen.draw(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1},
                List.of(new AnnotatedScreen.Mark(1, json.readTree("{\"x\":0,\"y\":0,\"w\":1,\"h\":1}"), null)))).isNull();
        assertThat(AnnotatedScreen.draw(null, List.of())).isNull();
    }

    private static boolean magenta(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 150 && c.getBlue() > 150 && c.getGreen() < 110;
    }
}
