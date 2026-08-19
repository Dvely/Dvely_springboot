package com.example.dvely.deployment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deployment.domain.value.DeployFailureCode;
import com.example.dvely.deployment.domain.value.DeployTargetType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DeploymentHistoryFailureCodeTest {

    @Test
    void retryingWithinTheLimitDoesNotStampAFailureCode() {
        DeploymentHistory history = newHistory();

        history.retry("일시적인 오류", Duration.ZERO);

        assertThat(history.getFailureCode()).isNull();
        assertThat(history.getErrorMessage()).isEqualTo("일시적인 오류");
    }

    @Test
    void exhaustingTheRetryLimitStampsRetryExhausted() {
        // attempt 는 도메인이 아니라 claim 쿼리가 올린다(SpringDataDeploymentHistoryRepository:42).
        // 그래서 한도에 도달한 상태를 직접 만들어 확인한다.
        DeploymentHistory history = historyWithAttempts(3, 3);

        history.retry("계속 실패", Duration.ZERO);

        assertThat(history.getFailureCode()).isEqualTo(DeployFailureCode.RETRY_EXHAUSTED);
        assertThat(history.getStatus()).isEqualTo(com.example.dvely.project.domain.value.DeployStatus.FAILED);
    }

    private DeploymentHistory historyWithAttempts(int attempt, int maxAttempts) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return new DeploymentHistory(
                1L, 1L, 11L, DeployTargetType.LATEST, "v1", "https://example.com",
                com.example.dvely.project.domain.value.DeployStatus.PENDING,
                null, "correlation-1", null, null, null, null, null, null, null, null,
                "task-1", "이전 사유", attempt, maxAttempts, null, null, null, now, now, null
        );
    }

    @Test
    void aSucceededDeploymentCarriesNoFailureCode() {
        DeploymentHistory history = newHistory();
        history.fail(DeployFailureCode.WORKFLOW_FAILED, "GitHub Actions workflow conclusion: failure");

        history.complete();

        // 재시도로 되살아난 이력이 옛 실패 분류를 달고 있으면 화면이 성공한 배포를 실패로 그린다.
        assertThat(history.getFailureCode()).isNull();
        assertThat(history.getErrorMessage()).isNull();
    }

    @Test
    void theCodeAndTheDetailAreStoredTogether() {
        DeploymentHistory history = newHistory();

        history.fail(DeployFailureCode.RESULT_UNKNOWN, "배포 결과를 확인할 수 없습니다. GitHub Actions 실행을 찾지 못했습니다.");

        assertThat(history.getFailureCode()).isEqualTo(DeployFailureCode.RESULT_UNKNOWN);
        assertThat(history.getErrorMessage()).contains("확인할 수 없습니다");
    }

    private DeploymentHistory newHistory() {
        return new DeploymentHistory(1L, 11L, DeployTargetType.LATEST, null, null, null);
    }
}
