package com.example.dvely.deviceshare.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deviceshare.support.PinTestImages;
import org.junit.jupiter.api.Test;

/** 화면 지문(dHash)과 그림 줄이기(설계 §15.5·§15.9). */
class PinImagesTest {

    @Test
    void 같은_화면은_지문이_같고_스크롤하거나_다른_화면이면_10비트를_넘게_다르다() {
        long same = hash(PinTestImages.screen(0, 7));
        long again = hash(PinTestImages.screen(0, 7));
        long scrolled = hash(PinTestImages.screen(300, 7));
        long other = hash(PinTestImages.screen(0, 8));

        assertThat(Long.bitCount(same ^ again)).isZero();
        assertThat(Long.bitCount(same ^ scrolled)).isGreaterThan(ShareSession.SAME_VIEW_BITS);
        assertThat(Long.bitCount(same ^ other)).isGreaterThan(ShareSession.SAME_VIEW_BITS);
    }

    @Test
    void 폭_전체를_채운_띠로_된_화면도_스크롤하면_다르다() {
        // 앱 화면은 가로 띠다. 가로 이웃만 비교하면 모든 칸이 같아 스크롤을 못 알아본다(실측에서 고친 것)
        long top = hash(PinTestImages.bands(0, 3));
        assertThat(Long.bitCount(top ^ hash(PinTestImages.bands(0, 3)))).isZero();
        assertThat(Long.bitCount(top ^ hash(PinTestImages.bands(200, 3)))).isGreaterThan(ShareSession.SAME_VIEW_BITS);
    }

    @Test
    void 조금_스크롤한_화면은_같은_화면으로_본다() {
        // 한두 줄(4px) 움직인 것은 같은 화면이다. 뷰어가 묻지 않아도 된다
        long same = hash(PinTestImages.screen(0, 7));
        long nudged = hash(PinTestImages.screen(4, 7));

        assertThat(Long.bitCount(same ^ nudged)).isLessThanOrEqualTo(ShareSession.SAME_VIEW_BITS);
    }

    @Test
    void 읽지_못하는_그림은_지문이_없다() {
        assertThat(PinImages.dhash(PinImages.read(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}))).isEmpty();
        assertThat(PinImages.dhash(null)).isEmpty();
    }

    @Test
    void 상한을_넘는_그림은_줄이고_작으면_그대로_둔다() {
        byte[] big = PinTestImages.noise(700, 700, 1);
        assertThat(big.length).isGreaterThan(256 * 1024);

        byte[] kept = PinImages.shrink(big, 256 * 1024);
        assertThat(kept.length).isLessThanOrEqualTo(256 * 1024);
        assertThat(PinImages.read(kept)).isNotNull();

        byte[] small = PinTestImages.screen(0, 1);
        assertThat(PinImages.shrink(small, 256 * 1024)).isSameAs(small);
        assertThat(PinImages.shrink(new byte[300], 100)).as("읽지 못하면 버린다").isNull();
    }

    private static long hash(byte[] jpeg) {
        return PinImages.dhash(PinImages.read(jpeg)).orElseThrow();
    }
}
