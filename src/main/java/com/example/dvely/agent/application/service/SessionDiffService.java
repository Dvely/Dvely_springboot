package com.example.dvely.agent.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.agent.infrastructure.docker.UserContainerInfo;
import com.example.dvely.agent.infrastructure.docker.UserContainerRegistry;
import com.example.dvely.agent.presentation.dto.CommitDiffResponse;
import com.example.dvely.agent.presentation.dto.DiffStatsDto;
import com.example.dvely.agent.presentation.dto.FileDiffDto;
import com.example.dvely.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionDiffService {

    private static final String WORKSPACE = "/workspace/app";

    private final DockerContainerService dockerService;
    private final UserContainerRegistry  containerRegistry;

    public CommitDiffResponse getDiff(Long userId, String sessionId) {
        UserContainerInfo info = containerRegistry.find(userId)
                .orElseThrow(() -> new NotFoundException("실행 중인 Docker 컨테이너가 없습니다"));

        String containerId = info.containerId();
        containerRegistry.touch(userId);

        String rawDiff = extractRawDiff(containerId);
        log.debug("[SessionDiff] containerId={} rawDiff length={}", containerId, rawDiff.length());

        List<FileDiffDto> files = parseGitDiff(rawDiff);
        int totalAdditions = files.stream().mapToInt(FileDiffDto::additions).sum();
        int totalDeletions = files.stream().mapToInt(FileDiffDto::deletions).sum();

        return new CommitDiffResponse(
                sessionId,
                new DiffStatsDto(totalAdditions, totalDeletions, totalAdditions + totalDeletions),
                files
        );
    }

    private String extractRawDiff(String containerId) {
        // 케이스 1: git 없음 → init + add -A + diff --cached
        // 케이스 2: git 있지만 커밋 없음 → add -A + diff --cached
        // 케이스 3: git + 커밋 있음 → diff HEAD
        String script = """
                cd %s 2>/dev/null || { echo ''; exit 0; }
                if ! git rev-parse --git-dir > /dev/null 2>&1; then
                  git init -q
                  git config user.email 'agent@dvely.app'
                  git config user.name 'Dvely Agent'
                  git add -A
                  git diff --cached --patch --no-color
                elif git rev-parse HEAD > /dev/null 2>&1; then
                  git diff HEAD --patch --no-color
                  git diff --cached --patch --no-color
                else
                  git add -A
                  git diff --cached --patch --no-color
                fi
                """.formatted(WORKSPACE);

        return dockerService.exec(containerId, script.strip());
    }

    // ── unified diff 파싱 ──────────────────────────────────────────────────────

    private List<FileDiffDto> parseGitDiff(String rawDiff) {
        List<FileDiffDto> result = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) return result;

        // "diff --git" 경계로 섹션 분리
        String[] sections = rawDiff.split("(?=diff --git )");

        for (String section : sections) {
            if (section.isBlank() || !section.startsWith("diff --git")) continue;

            String filename = extractFilename(section);
            if (filename == null) continue;

            String status    = detectStatus(section);
            String patch     = extractPatch(section);
            int    additions = countAdditions(patch);
            int    deletions = countDeletions(patch);

            result.add(new FileDiffDto(filename, status, additions, deletions, additions + deletions, patch));
        }
        return result;
    }

    private String extractFilename(String section) {
        for (String line : section.split("\n")) {
            if (line.startsWith("+++ b/")) return line.substring(6);
        }
        // 삭제된 파일: +++ /dev/null, --- a/filename
        for (String line : section.split("\n")) {
            if (line.startsWith("--- a/")) return line.substring(6);
        }
        return null;
    }

    private String detectStatus(String section) {
        if (section.contains("new file mode"))    return "added";
        if (section.contains("deleted file mode")) return "deleted";
        if (section.contains("rename from"))       return "renamed";
        return "modified";
    }

    private String extractPatch(String section) {
        int hunkStart = section.indexOf("@@");
        if (hunkStart < 0) return null;
        return section.substring(hunkStart);
    }

    private int countAdditions(String patch) {
        if (patch == null) return 0;
        return (int) patch.lines()
                .filter(l -> l.startsWith("+") && !l.startsWith("+++"))
                .count();
    }

    private int countDeletions(String patch) {
        if (patch == null) return 0;
        return (int) patch.lines()
                .filter(l -> l.startsWith("-") && !l.startsWith("---"))
                .count();
    }
}
