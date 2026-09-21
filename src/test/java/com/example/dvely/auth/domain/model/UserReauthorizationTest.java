package com.example.dvely.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.domain.value.GithubId;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 액세스 토큰 만료와 "유저가 재인증해야 함"은 다른 상태다. 리프레시 토큰이 살아 있으면
 * GitHub 을 호출하는 경로들이 알아서 재발급하므로 유저에게 시킬 일이 없다. 이 둘을 한 값으로
 * 묶어 내려보내면 FE 가 8시간마다 재인증 모달을 띄우게 된다.
 */
class UserReauthorizationTest {

    @Test
    void expiredAccessTokenAloneDoesNotRequireReauthorization() {
        User user = linked(LocalDateTime.now().minusMinutes(22));

        assertThat(user.isUserAccessTokenExpired()).isTrue();
        assertThat(user.needsGithubAppReauthorization()).isFalse();
    }

    @Test
    void liveAccessTokenRequiresNothing() {
        User user = linked(LocalDateTime.now().plusHours(6));

        assertThat(user.isUserAccessTokenExpired()).isFalse();
        assertThat(user.needsGithubAppReauthorization()).isFalse();
    }

    @Test
    void reauthorizationIsRequiredOnceTheRefreshTokenIsGone() {
        // bad_refresh_token 을 받으면 clearGithubAppToken 이 셋을 모두 비운다. 그때부터가
        // 진짜 재인증 구간이다.
        User user = linked(LocalDateTime.now().plusHours(6));
        user.clearGithubAppToken();

        assertThat(user.needsGithubAppReauthorization()).isTrue();
    }

    @Test
    void reauthorizationIsRequiredWhenTheRefreshTokenOutlivedIts180Days() {
        // 리프레시 토큰 수명은 마지막 갱신 시점부터 180일이다. 액세스 토큰 만료 시각에서
        // 발급 시각(-8h)을 되짚어 계산하므로, 181일 전에 발급된 상태를 만들면 지나 있어야 한다.
        User user = linked(LocalDateTime.now().minusDays(181).plusHours(8));

        assertThat(user.getRefreshTokenExpiresAt()).isBefore(LocalDateTime.now());
        assertThat(user.needsGithubAppReauthorization()).isTrue();
    }

    /**
     * 예전에는 이 경우를 "재인증 필요" 로 봤다. 그 결과 설치도 안 한 사용자에게 재인증 화면이
     * 뜨고, 그 버튼이 부르는 경로는 설치를 전제하므로 403 만 돌려줬다 — 눌러도 같은 자리로
     * 돌아오는 고리가 됐다(#367, 운영에서 7회 반복 관측).
     *
     * <p>미설치는 재인증이 아니라 <b>설치</b>다. 화면이 가야 할 곳이 다르다.</p>
     */
    @Test
    void neverLinkedUserNeedsInstallationNotReauthorization() {
        User user = new User(new GithubId("1"), "octo", null);

        assertThat(user.hasGithubAppInstalled()).isFalse();
        assertThat(user.getRefreshTokenExpiresAt()).isNull();
        assertThat(user.needsGithubAppReauthorization()).isFalse();
    }

    /**
     * 설치는 했는데 리프레시 토큰이 사라진 경우는 여전히 재인증이다 — 권한은 그대로고 토큰만
     * 다시 받으면 된다. 위 경우와 갈리는 지점이 설치 여부다.
     */
    @Test
    void installedUserWithoutRefreshTokenStillNeedsReauthorization() {
        User user = new User(new GithubId("1"), "octo", null);
        user.updateInstallationId(1L);

        assertThat(user.hasGithubAppInstalled()).isTrue();
        assertThat(user.needsGithubAppReauthorization()).isTrue();
    }

    /**
     * 이름대로 <b>App 이 연동된</b> 사용자를 만든다. 예전에는 설치 ID 없이 토큰만 넣었는데,
     * 재인증 판정이 설치 여부를 보지 않던 때라 티가 나지 않았다 — 이제는 설치가 전제다(#367).
     */
    private User linked(LocalDateTime accessExpiresAt) {
        User user = new User(new GithubId("1"), "octo", null);
        user.updateInstallationId(1L);
        user.updateUserToken("access", "refresh", accessExpiresAt);
        return user;
    }
}
