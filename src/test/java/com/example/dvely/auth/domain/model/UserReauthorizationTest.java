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

    @Test
    void neverLinkedUserRequiresReauthorization() {
        User user = new User(new GithubId("1"), "octo", null);

        assertThat(user.getRefreshTokenExpiresAt()).isNull();
        assertThat(user.needsGithubAppReauthorization()).isTrue();
    }

    private User linked(LocalDateTime accessExpiresAt) {
        User user = new User(new GithubId("1"), "octo", null);
        user.updateUserToken("access", "refresh", accessExpiresAt);
        return user;
    }
}
