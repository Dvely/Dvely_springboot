package com.example.dvely.agent.infrastructure.git;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 서버 측 git(#523)이 남긴 작업 디렉터리를 치운다. 작업마다 끝나면 지우지만, 프로세스가 비정상 종료하면
 * 남는다(설계 6).
 *
 * <ul>
 *   <li>작업 디렉터리 바로 아래의 {@code qeploy-git-*} 디렉터리만 본다. 그 밖의 것은 건드리지 않는다.</li>
 *   <li>지금 열려 있는 작업 공간은 건너뛴다. 그 위에 나이 기준({@link #MAX_AGE})을 한 번 더 둔다 —
 *       다른 인스턴스가 같은 위치를 쓰는 경우에도 진행 중인 작업을 지우지 않는다.</li>
 *   <li>심볼릭 링크는 따라가지 않는다. 링크는 링크 자체만 지우고, 그 대상은 건드리지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServerGitWorkDirSweeper {

    static final Duration MAX_AGE = Duration.ofHours(1);

    private final ServerGitProperties properties;

    @Scheduled(initialDelayString = "${qeploy.git.server-side.workdir-sweep-initial-delay-ms:30000}",
            fixedDelayString = "${qeploy.git.server-side.workdir-sweep-interval-ms:1800000}")
    public void sweep() {
        try {
            int removed = sweep(Path.of(properties.workDir()), Instant.now());
            if (removed > 0) {
                log.info("[ServerGit] 남아 있던 작업 디렉터리 {}개를 지웠습니다", removed);
            }
        } catch (RuntimeException e) {
            log.warn("[ServerGit] 작업 디렉터리 정리 실패(다음 주기에 다시 시도): {}", e.toString());
        }
    }

    /** @return 지운 작업 디렉터리 수 */
    int sweep(Path workDir, Instant now) {
        if (!Files.isDirectory(workDir, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(workDir, ServerGitWorkspace.DIRECTORY_PREFIX + "*")) {
            for (Path child : children) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || ServerGitWorkspace.isOpen(child)) {
                    continue;   // 링크·파일은 우리가 만든 작업 디렉터리가 아니다. 열린 것은 진행 중이다
                }
                Instant modified = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toInstant();
                if (modified.isAfter(now.minus(MAX_AGE))) {
                    continue;
                }
                deleteTree(child);
                removed++;
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return removed;
    }

    /** 링크를 따라가지 않고 지운다. 링크를 만나면 링크만 지운다. */
    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
