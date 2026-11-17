package com.example.dvely.deviceshare.application.session;

import java.util.List;

/**
 * 보는 사람이 글을 붙여 보내려고 가져간 짚기 결과(설계 §14.1·§15.5). 세션 메모리에서 빠진다.
 *
 * @param viewerName 짚은 사람 이름
 * @param owner      내 제어 링크로 들어온 사람인지
 * @param marks      짚은 곳 ①②… 순서. 하나 이상
 * @param screen     짚은 화면 JPEG(가장 나중에 짚은 곳의 것). 없으면 null
 */
public record ClaimedPin(String viewerName, boolean owner, List<Mark> marks, byte[] screen) {

    public ClaimedPin {
        marks = List.copyOf(marks);
        if (marks.isEmpty()) {
            throw new IllegalArgumentException("marks");
        }
    }

    /**
     * 짚은 곳 하나.
     *
     * @param crop   기기가 잘라 보낸 JPEG. 없으면 null
     * @param screen 이 짚기의 화면 JPEG(같은 화면이면 다른 짚기의 것을 함께 쓴다). 없으면 null
     */
    public record Mark(PinResolution resolution, byte[] crop, byte[] screen) {

        @Override
        public String toString() {
            return "Mark[probe=" + resolution.probeId() + "]";
        }
    }

    /** ①의 결과 */
    public PinResolution resolution() {
        return marks.getFirst().resolution();
    }

    /** ①을 기기가 잘라 보낸 것 */
    public byte[] crop() {
        return marks.getFirst().crop();
    }

    @Override
    public String toString() {
        return "ClaimedPin[probes=" + marks.stream().map(m -> m.resolution().probeId()).toList() + ", owner=" + owner + "]";
    }
}
