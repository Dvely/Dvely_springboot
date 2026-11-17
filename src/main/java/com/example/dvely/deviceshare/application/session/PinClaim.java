package com.example.dvely.deviceshare.application.session;

import java.util.List;
import java.util.Optional;

/**
 * 여러 곳 짚기 가져가기(§15.9). 하나라도 없으면 아무것도 가져가지 않고, 없는 짚기를 알려 뷰어가 그것만 다시 짚게 한다.
 *
 * @param claimed 모두 있으면 가져간 결과
 * @param missing 없거나 시간이 지난 짚기 id. 다 있으면 빈 목록
 */
public record PinClaim(Optional<ClaimedPin> claimed, List<String> missing) {

    static PinClaim of(ClaimedPin pin) {
        return new PinClaim(Optional.of(pin), List.of());
    }

    static PinClaim missing(List<String> probeIds) {
        return new PinClaim(Optional.empty(), List.copyOf(probeIds));
    }
}
