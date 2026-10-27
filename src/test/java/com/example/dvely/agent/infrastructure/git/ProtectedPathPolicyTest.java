package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.ChangedPath;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 승인이 필요한 경로 판정(#508)과 서버 측 허용목록(설계 3.1). */
class ProtectedPathPolicyTest {

    private static final String WORKFLOW = "name: deploy\non: workflow_dispatch\n";

    @ParameterizedTest
    @ValueSource(strings = {".github/workflows/x.yml", ".GITHUB/Workflows/x.yml", "./.github//workflows/x.yml",
            "/.github/workflows/x.yml", ".github\\workflows\\x.yml", ".github/actions/a/action.yml", ".github"})
    void guardedSpellings(String path) {
        assertThat(ProtectedPathPolicy.isGuarded(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/.github/workflows/x.yml", ".github-pages/i.html", ".github/ISSUE_TEMPLATE/b.md", ""})
    void otherPaths(String path) {
        assertThat(ProtectedPathPolicy.isGuarded(path)).isFalse();
    }

    @Test
    void contentThatMatchesTheServerAllowlistByteForBytePasses() {
        ObjectId allowed = ProtectedPathPolicy.blobIdOf(WORKFLOW);
        ProtectedPathPolicy policy = new ProtectedPathPolicy(Set.of(allowed));

        assertThat(policy.requiringApproval(List.of(new ChangedPath(".github/workflows/deploy.yml", allowed)), Set.of()))
                .isEmpty();
    }

    @Test
    void oneDifferentByteRequiresApproval() {
        ProtectedPathPolicy policy = new ProtectedPathPolicy(Set.of(ProtectedPathPolicy.blobIdOf(WORKFLOW)));
        ObjectId tampered = ProtectedPathPolicy.blobIdOf(WORKFLOW + " ");

        assertThat(policy.requiringApproval(List.of(new ChangedPath(".github/workflows/deploy.yml", tampered)), Set.of()))
                .containsExactly(".github/workflows/deploy.yml");
    }

    @Test
    void serverGeneratedContentPassesOnlyForThatCall() {
        ProtectedPathPolicy policy = new ProtectedPathPolicy(Set.of());
        ObjectId generated = ProtectedPathPolicy.blobIdOf(WORKFLOW);
        List<ChangedPath> changes = List.of(new ChangedPath(".github/workflows/deploy.yml", generated));

        assertThat(policy.requiringApproval(changes, Set.of(generated))).isEmpty();
        assertThat(policy.requiringApproval(changes, Set.of())).containsExactly(".github/workflows/deploy.yml");
    }

    @Test
    void deletingAProtectedFileAlwaysRequiresApproval() {
        ProtectedPathPolicy policy = new ProtectedPathPolicy(Set.of(ProtectedPathPolicy.blobIdOf(WORKFLOW)));

        assertThat(policy.requiringApproval(List.of(new ChangedPath(".github/workflows/deploy.yml", null)), Set.of()))
                .containsExactly(".github/workflows/deploy.yml");
    }

    @Test
    void theBundledAllowlistLoadsFromTheServerResource() {
        // 리소스는 서버에만 있다. 지금은 비어 있어도 읽기 자체가 깨지면 안 된다.
        assertThat(new ProtectedPathPolicy().requiringApproval(List.of(), Set.of())).isEmpty();
    }
}
