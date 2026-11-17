package com.example.dvely.deviceshare.domain.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * 짚은 순간의 이미지. 모두 없을 수 있다.
 *
 * @param crop      ①에서 고른 범위를 잘라 낸 JPEG
 * @param screen    그 순간의 화면 JPEG
 * @param annotated 짚은 곳마다 테두리와 번호를 그린 화면 JPEG(여러 곳일 때, 설계 §15.10)
 * @param markCrops ②부터 짚은 곳마다 잘라 낸 JPEG. 키는 번호(2~5)
 */
public record FeedbackImages(byte[] crop, byte[] screen, byte[] annotated, Map<Integer, byte[]> markCrops) {

    public static final FeedbackImages NONE = new FeedbackImages(null, null);

    public FeedbackImages {
        markCrops = markCrops == null ? Map.of() : Map.copyOf(markCrops);
    }

    public FeedbackImages(byte[] crop, byte[] screen) {
        this(crop, screen, null, Map.of());
    }

    public boolean hasCrop() {
        return crop != null && crop.length > 0;
    }

    public boolean hasScreen() {
        return screen != null && screen.length > 0;
    }

    public boolean hasAnnotated() {
        return annotated != null && annotated.length > 0;
    }

    @Override
    public String toString() {
        // 이미지 바이트는 로그에 나갈 까닭이 없다
        Map<Integer, Integer> marks = new TreeMap<>();
        markCrops.forEach((n, bytes) -> marks.put(n, bytes == null ? 0 : bytes.length));
        return "FeedbackImages[crop=" + length(crop) + "B, screen=" + length(screen) + "B, annotated=" + length(annotated)
                + "B, marks=" + marks + "]";
    }

    private static int length(byte[] bytes) {
        return bytes == null ? 0 : bytes.length;
    }
}
