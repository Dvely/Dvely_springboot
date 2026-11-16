package com.example.dvely.deviceshare.domain.model;

/**
 * 짚은 순간의 이미지. 둘 다 없을 수 있다.
 *
 * @param crop   짚은 요소를 잘라 낸 JPEG
 * @param screen 그 순간의 화면 JPEG
 */
public record FeedbackImages(byte[] crop, byte[] screen) {

    public static final FeedbackImages NONE = new FeedbackImages(null, null);

    public boolean hasCrop() {
        return crop != null && crop.length > 0;
    }

    public boolean hasScreen() {
        return screen != null && screen.length > 0;
    }

    @Override
    public String toString() {
        // 이미지 바이트는 로그에 나갈 까닭이 없다
        return "FeedbackImages[crop=" + (crop == null ? 0 : crop.length) + "B, screen="
                + (screen == null ? 0 : screen.length) + "B]";
    }
}
