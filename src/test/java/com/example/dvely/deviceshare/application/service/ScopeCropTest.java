package com.example.dvely.deviceshare.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 사람이 고른 범위의 그림(설계 §15.10). 요소 칸은 기기 것, 화면 칸은 화면, 그 밖은 서버가 화면에서 자른다. */
class ScopeCropTest {

    private static final byte[] DEVICE_CROP = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 덩어리_칸은_화면에서_테두리와_여백만큼_잘라_긴_변을_800px_로_줄인다() throws Exception {
        byte[] screen = jpeg(1600, 1000);
        JsonNode scope = json.readTree("{\"kind\":\"group\",\"bounds\":{\"x\":0,\"y\":0.5,\"w\":1,\"h\":0.5}}");

        byte[] cut = ShareFeedbackService.selectedCrop(scope, DEVICE_CROP, screen);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(cut));
        // 1600×(500+여백 24) → 긴 변 800 으로
        assertThat(image.getWidth()).isEqualTo(800);
        assertThat(image.getHeight()).isBetween(255, 265);
        assertThat(new Color(image.getRGB(400, 200)).getBlue()).as("아래 절반의 파란색").isGreaterThan(180);
    }

    @Test
    void 요소_칸은_기기가_잘라_보낸_것이고_화면_칸은_화면이다() throws Exception {
        byte[] screen = jpeg(100, 100);
        assertThat(ShareFeedbackService.selectedCrop(json.readTree("{\"kind\":\"element\"}"), DEVICE_CROP, screen))
                .isSameAs(DEVICE_CROP);
        assertThat(ShareFeedbackService.selectedCrop(json.readTree("{\"kind\":\"screen\"}"), DEVICE_CROP, screen))
                .isSameAs(screen);
    }

    @Test
    void 끈_영역은_기기가_사각형으로_잘라_보낸_것이고_없으면_서버가_자른다() throws Exception {
        byte[] screen = jpeg(400, 400);
        JsonNode region = json.readTree("{\"kind\":\"region\",\"bounds\":{\"x\":0,\"y\":0.5,\"w\":0.5,\"h\":0.5}}");
        assertThat(ShareFeedbackService.selectedCrop(region, DEVICE_CROP, screen)).isSameAs(DEVICE_CROP);
        BufferedImage cut = ImageIO.read(new ByteArrayInputStream(ShareFeedbackService.selectedCrop(region, null, screen)));
        assertThat(cut.getWidth()).isBetween(200, 215);
    }

    @Test
    void 화면을_읽지_못하면_기기가_잘라_보낸_것으로_둔다() throws Exception {
        JsonNode scope = json.readTree("{\"kind\":\"component\",\"bounds\":{\"x\":0,\"y\":0,\"w\":0.5,\"h\":0.5}}");
        assertThat(ShareFeedbackService.selectedCrop(scope, DEVICE_CROP, new byte[] {1, 2, 3})).isSameAs(DEVICE_CROP);
        assertThat(ShareFeedbackService.selectedCrop(scope, DEVICE_CROP, null)).isSameAs(DEVICE_CROP);
    }

    /** 위 절반은 흰색, 아래 절반은 파란색 */
    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(37, 99, 235));
        g.fillRect(0, height / 2, width, height - height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }
}
