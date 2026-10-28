package com.example.dvely.change.application.service;

import com.example.dvely.change.application.result.ResultScope.FileChange;
import com.example.dvely.change.application.result.ResultScope.Origin;
import java.util.ArrayList;
import java.util.List;

/**
 * 서버가 만든 unified diff(이름 바꿈 감지 없음)에서 파일별 변경을 읽는다(#532). push 를 승인 뒤로 미뤄
 * 아직 preview 에 없는 이번 작업의 파일을 반영 범위에 더할 때 쓴다.
 */
final class UnifiedDiffFiles {

    private UnifiedDiffFiles() {
    }

    static List<FileChange> parse(String diff) {
        List<FileChange> files = new ArrayList<>();
        if (diff == null || diff.isEmpty()) {
            return files;
        }
        Builder current = null;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                if (current != null) {
                    files.add(current.build());
                }
                current = new Builder(pathOf(line));
            } else if (current == null) {
                continue;
            } else if (line.startsWith("new file mode")) {
                current.status = "added";
            } else if (line.startsWith("deleted file mode")) {
                current.status = "removed";
            } else if (line.startsWith("Binary files ")) {
                current.binary = true;
            } else if (line.startsWith("+++ ") || line.startsWith("--- ")) {
                continue;
            } else if (line.startsWith("+")) {
                current.additions++;
            } else if (line.startsWith("-")) {
                current.deletions++;
            }
        }
        if (current != null) {
            files.add(current.build());
        }
        return files;
    }

    /** {@code diff --git a/x b/x} 에서 b 쪽 경로. 이름 바꿈을 감지하지 않으므로 a·b 가 같다. */
    static String pathOf(String header) {
        String rest = header.substring("diff --git ".length());
        int quoted = rest.indexOf(" \"b/");   // 특수 문자가 든 경로는 따옴표로 감싸진다
        if (quoted >= 0 && rest.endsWith("\"")) {
            return rest.substring(quoted + 4, rest.length() - 1);
        }
        int split = rest.indexOf(" b/");
        return split >= 0 ? rest.substring(split + 3) : rest;
    }

    private static final class Builder {
        private final String path;
        private String status = "modified";
        private boolean binary;
        private int additions;
        private int deletions;

        private Builder(String path) {
            this.path = path;
        }

        private FileChange build() {
            return new FileChange(path, status, binary ? null : additions, binary ? null : deletions, null,
                    List.of(Origin.THIS_TASK));
        }
    }
}
