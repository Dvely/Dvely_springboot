package com.example.dvely.deviceshare.application.session;

/**
 * 보는 사람이 글을 붙여 보내려고 가져간 짚기 결과(설계 §14.1). 세션 메모리에서 빠진다.
 *
 * @param viewerName 짚은 사람 이름
 * @param owner      내 제어 링크로 들어온 사람인지
 * @param crop       잘라 낸 JPEG. 없으면 null
 * @param screen     화면 JPEG. 없으면 null
 */
public record ClaimedPin(String viewerName, boolean owner, PinResolution resolution, byte[] crop, byte[] screen) {

    @Override
    public String toString() {
        return "ClaimedPin[probe=" + resolution.probeId() + ", owner=" + owner + "]";
    }
}
