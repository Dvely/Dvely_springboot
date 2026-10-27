package com.example.dvely.agent.infrastructure.git;

/**
 * 스냅샷이 파일 수·바이트 상한을 넘었다. 메시지는 사용자에게 그대로 보인다(설계 3.1).
 * 상한은 {@code .git}·{@code node_modules} 를 뺀 뒤의 수로 센다.
 */
public class SnapshotLimitExceededException extends ServerGitException {

    public SnapshotLimitExceededException(int files, int maxFiles, long bytes, long maxBytes) {
        super("변경 내용이 너무 커서 저장소에 반영하지 못했습니다(파일 " + files + "개 / 상한 " + maxFiles + "개, "
                + megabytes(bytes) + " MB / 상한 " + megabytes(maxBytes) + " MB). "
                + "빌드 산출물이나 큰 파일이 .gitignore 에 들어 있는지 확인해 주세요.", null);
    }

    private static String megabytes(long bytes) {
        return String.format("%.1f", bytes / 1024.0 / 1024.0);
    }
}
