package com.example.dvely.deployment.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GithubRepoClientTest {

    // GitHub compare API 는 브랜치가 없을 때도, 두 브랜치에 공통 조상이 없을 때도 404 를 준다.
    // 예전에는 둘 다 false 로 삼켰는데, 그러면 호출부가 "병합할 것 없음" 경로로 빠져서 아무것도
    // 반영하지 않은 채 Change 를 MERGED 로 기록하고 사용자에게는 반영됐다고 알린다.

    @Test
    void treatsAMissingHeadBranchAsNothingToMerge() {
        // preview 브랜치를 아직 안 올린 정상 상태. 조용히 넘어가는 게 맞다.
        assertThat(GithubRepoClient.interpretCompareNotFound(
                "octo/app", "main", "preview", false, false)).isFalse();
    }

    @Test
    void failsLoudlyWhenTheMergeTargetBranchDoesNotExist() {
        // 기본 브랜치가 master 인 저장소를 연결해두고 main 으로 병합하려는 경우.
        assertThatThrownBy(() -> GithubRepoClient.interpretCompareNotFound(
                "octo/app", "main", "preview", true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("병합 대상 브랜치가 저장소에 없습니다")
                .hasMessageContaining("main");
    }

    @Test
    void failsLoudlyWhenBothBranchesExistButShareNoAncestor() {
        // 저장소를 연결할 때 preview 를 기본 브랜치에서 갈라내지 않은 경우. 조용히 false 를
        // 돌려주면 반영되지 않은 작업이 MERGED 로 기록된다.
        assertThatThrownBy(() -> GithubRepoClient.interpretCompareNotFound(
                "octo/app", "main", "preview", true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("공통 조상이 없어")
                .hasMessageContaining("octo/app");
    }
}
