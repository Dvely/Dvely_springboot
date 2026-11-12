package com.example.dvely.deviceshare;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deviceshare.application.session.ShareFrames;
import com.example.dvely.deviceshare.domain.service.ShareTokens;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShareTokensTest {

    @Test
    void 보기_토큰은_26자_소문자_base32_이고_하위_도메인_라벨로_쓸_수_있다() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String token = ShareTokens.newViewToken();
            assertThat(token).hasSize(26).matches("[a-z2-7]{26}");
            assertThat(ShareTokens.looksLikeViewToken(token)).isTrue();
            seen.add(token);
        }
        assertThat(seen).hasSize(2_000);
        assertThat(ShareTokens.looksLikeViewToken("ABCDEFGHIJKLMNOPQRSTUVWXYZ")).isFalse();
        assertThat(ShareTokens.looksLikeViewToken("abc")).isFalse();
        assertThat(ShareTokens.looksLikeViewToken("api")).isFalse();
    }

    @Test
    void 제어_비밀은_해시로만_맞춰_본다() {
        String secret = ShareTokens.newControlSecret();
        String hash = ShareTokens.sha256Hex(secret);
        assertThat(hash).hasSize(64).doesNotContain(secret);
        assertThat(ShareTokens.matches(secret, hash)).isTrue();
        assertThat(ShareTokens.matches(secret + "x", hash)).isFalse();
        assertThat(ShareTokens.matches(null, hash)).isFalse();
        assertThat(ShareTokens.matches("", hash)).isFalse();
    }

    @Test
    void 프레임은_kind_1바이트_stream_id_4바이트_뒤에_payload() {
        ByteBuffer encoded = ShareFrames.encode(ShareFrames.SCREEN_CONFIG, "{\"width\":390}");
        assertThat(encoded.remaining()).isEqualTo(5 + 13);
        assertThat(encoded.get(0) & 0xff).isEqualTo(0x51);
        assertThat(encoded.getInt(1)).isZero();

        ShareFrames.Frame decoded = ShareFrames.decode(encoded);
        assertThat(decoded.kind()).isEqualTo(ShareFrames.SCREEN_CONFIG);
        assertThat(decoded.streamId()).isZero();
        assertThat(decoded.payloadText()).isEqualTo("{\"width\":390}");

        ShareFrames.Frame empty = ShareFrames.decode(ShareFrames.encode(ShareFrames.HELLO, new byte[0]));
        assertThat(empty.payload()).isEmpty();
        assertThat(new String(ShareFrames.encode(ShareFrames.INPUT, "가").array(), 5, 3, StandardCharsets.UTF_8))
                .isEqualTo("가");
    }
}
