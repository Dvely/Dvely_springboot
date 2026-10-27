package com.example.dvely.agent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 남은 서버 git 작업 디렉터리 정리(#523 설계 6). 오래된 우리 디렉터리만, 링크는 따라가지 않고. */
class ServerGitWorkDirSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @TempDir Path workDir;
    @TempDir Path outside;

    private final ServerGitWorkDirSweeper sweeper = new ServerGitWorkDirSweeper(null);

    @Test
    void onlyOldLeftoverWorkDirectoriesAreRemoved() throws Exception {
        Path old = workDirectory("qeploy-git-old", Duration.ofHours(3));
        Path young = workDirectory("qeploy-git-young", Duration.ofMinutes(10));
        Path notOurs = Files.createDirectory(workDir.resolve("something-else"));
        age(notOurs, Duration.ofDays(5));

        assertThat(sweeper.sweep(workDir, NOW)).isEqualTo(1);

        assertThat(old).doesNotExist();
        assertThat(young).exists();
        assertThat(notOurs).exists();
    }

    @Test
    void aWorkspaceThatIsStillOpenIsKeptEvenIfItLooksOld() throws Exception {
        try (ServerGitWorkspace open = ServerGitWorkspace.create(workDir, GitTestFixtures.limits(10, 1000, workDir))) {
            Path dir;
            try (var children = Files.list(workDir)) {
                dir = children.filter(p -> p.getFileName().toString().startsWith("qeploy-git-")).findFirst().orElseThrow();
            }
            age(dir, Duration.ofDays(2));

            assertThat(sweeper.sweep(workDir, NOW)).isZero();
            assertThat(dir).exists();
        }
    }

    @Test
    void linksAreNeverFollowed() throws Exception {
        Path target = Files.createDirectory(outside.resolve("precious"));
        Files.writeString(target.resolve("keep.txt"), "keep");
        // 작업 디렉터리 안에서 바깥을 가리키는 링크 — 링크만 지워지고 대상은 남아야 한다.
        Path old = workDirectory("qeploy-git-with-link", Duration.ofHours(3));
        Files.createSymbolicLink(old.resolve("escape"), target);
        age(old, Duration.ofHours(3));
        // 이름은 우리 것 같지만 링크인 최상위 항목 — 건너뛴다.
        Path topLink = Files.createSymbolicLink(workDir.resolve("qeploy-git-link"), target);

        sweeper.sweep(workDir, NOW);

        assertThat(old).doesNotExist();
        assertThat(target.resolve("keep.txt")).hasContent("keep");
        assertThat(Files.isSymbolicLink(topLink)).isTrue();
    }

    @Test
    void aMissingWorkDirectoryIsFine() {
        assertThat(sweeper.sweep(workDir.resolve("does-not-exist"), NOW)).isZero();
    }

    private Path workDirectory(String name, Duration age) throws Exception {
        Path dir = Files.createDirectory(workDir.resolve(name));
        Files.writeString(dir.resolve("HEAD"), "ref: refs/heads/main");
        age(dir, age);
        return dir;
    }

    private static void age(Path path, Duration age) throws Exception {
        Files.setLastModifiedTime(path, FileTime.from(NOW.minus(age)));
    }
}
