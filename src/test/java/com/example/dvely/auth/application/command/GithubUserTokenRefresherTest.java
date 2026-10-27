package com.example.dvely.auth.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.auth.application.port.out.GithubAppPort;
import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.common.exception.GithubAppNotInstalledException;
import com.example.dvely.common.exception.GithubAppReauthorizationRequiredException;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GithubUserTokenRefresherTest {

    private UserRepository userRepository;
    private GithubAppPort githubAppPort;
    private GithubUserTokenRefresher refresher;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        githubAppPort = mock(GithubAppPort.class);
        refresher = new GithubUserTokenRefresher(userRepository, githubAppPort);
    }

    @Test
    void aFlowThatWaitedForTheLockUsesTheTokenTheWinnerCommitted() {
        // 이 클래스가 존재하는 이유다. 잠금을 기다리는 동안 앞선 흐름이 갱신을 마쳤다면,
        // 여기서 GitHub 을 부르는 순간 이미 회전된 리프레시 토큰을 쓰게 되고 bad_refresh_token
        // 을 맞아 사용자의 연동이 통째로 지워진다.
        when(userRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(userWithToken("fresh-access", "refresh", LocalDateTime.now().plusHours(7))));

        assertThat(refresher.refreshWithLock(10L)).isEqualTo("fresh-access");

        verify(githubAppPort, never()).refreshUserToken(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void theFirstFlowActuallyRefreshesAndStoresTheRotatedPair() {
        User user = userWithToken("old-access", "old-refresh", LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        when(githubAppPort.refreshUserToken("old-refresh"))
                .thenReturn(new GithubAppPort.GithubUserTokenInfo("new-access", "new-refresh", 28800L, 15811200L));

        assertThat(refresher.refreshWithLock(10L)).isEqualTo("new-access");

        assertThat(user.getGithubUserAccessToken()).isEqualTo("new-access");
        assertThat(user.getGithubUserRefreshToken()).isEqualTo("new-refresh");
        verify(userRepository).save(user);
    }

    @Test
    void anInstalledUserWithoutARefreshTokenIsAskedToReauthorize() {
        // 설치는 있다(installationId 55). 필요한 것은 설치가 아니라 재인증이다(#516).
        when(userRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(userWithToken(null, null, null)));

        assertThatThrownBy(() -> refresher.refreshWithLock(10L))
                .isInstanceOf(GithubAppReauthorizationRequiredException.class);

        verify(githubAppPort, never()).refreshUserToken(any());
    }

    @Test
    void aUserWithoutAnInstallationIsAskedToInstall() {
        User notInstalled = new User(10L, new GithubId("123"), "octo", "avatar", null, null, null, null);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(notInstalled));

        assertThatThrownBy(() -> refresher.refreshWithLock(10L))
                .isInstanceOf(GithubAppNotInstalledException.class);
    }

    @Test
    void aRejectedRefreshTokenClearsTheLinkage() {
        // 잠금 안에서 지운다. 예외를 던지지만 그 예외는 롤백 대상이 아니라
        // 이 지움은 커밋된다 — 롤백되면 다음 호출이 같은 무효 토큰으로 또 GitHub 에 간다.
        User user = userWithToken("old-access", "bad-refresh", LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        when(githubAppPort.refreshUserToken("bad-refresh"))
                .thenThrow(new IllegalStateException("bad_refresh_token"));

        assertThatThrownBy(() -> refresher.refreshWithLock(10L))
                .isInstanceOf(GithubAppReauthorizationRequiredException.class)
                .hasMessageContaining("다시 인증");

        assertThat(user.getGithubUserAccessToken()).isNull();
        assertThat(user.getGithubUserRefreshToken()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void theExceptionThrownAfterClearingIsExemptFromRollback() throws Exception {
        // 지운 뒤 던지는 예외가 noRollbackFor 에 없으면 롤백이 지움을 되돌린다. 예외 타입을 바꿀 때
        // 이 짝이 함께 바뀌어야 한다 — 단위 테스트는 트랜잭션이 없어 위 테스트만으로는 못 잡는다.
        Transactional tx = GithubUserTokenRefresher.class.getMethod("refreshWithLock", Long.class)
                .getAnnotation(Transactional.class);

        assertThat(tx.noRollbackFor()).contains(GithubAppReauthorizationRequiredException.class);
    }

    @Test
    void anUnrelatedFailureDoesNotClearTheLinkage() {
        // GitHub 이 잠깐 5xx 를 내는 것과 리프레시 토큰이 거부된 것은 다르다. 전자로 연동을
        // 지우면 멀쩡한 사용자가 재인증을 하게 된다.
        User user = userWithToken("old-access", "good-refresh", LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(user));
        when(githubAppPort.refreshUserToken("good-refresh"))
                .thenThrow(new IllegalStateException("502 Bad Gateway"));

        assertThatThrownBy(() -> refresher.refreshWithLock(10L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(user.getGithubUserRefreshToken()).isEqualTo("good-refresh");
        verify(userRepository, never()).save(any());
    }

    @Test
    void theFastPathReadsWithoutTakingTheLock() {
        when(userRepository.findById(10L))
                .thenReturn(Optional.of(userWithToken("fresh-access", "refresh", LocalDateTime.now().plusHours(7))));

        assertThat(refresher.readValidAccessToken(10L)).contains("fresh-access");

        verify(userRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void theFastPathReportsNothingWhenTheTokenIsExpired() {
        when(userRepository.findById(10L))
                .thenReturn(Optional.of(userWithToken("old-access", "refresh", LocalDateTime.now().minusMinutes(1))));

        assertThat(refresher.readValidAccessToken(10L)).isEmpty();
    }

    private User userWithToken(String accessToken, String refreshToken, LocalDateTime expiresAt) {
        return new User(
                10L,
                new GithubId("123"),
                "octo",
                "avatar",
                55L,
                accessToken,
                refreshToken,
                expiresAt
        );
    }
}
