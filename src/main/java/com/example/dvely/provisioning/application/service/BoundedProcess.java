package com.example.dvely.provisioning.application.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 외부 빌드 프로세스를 시간 제한 안에서 기다린다(#507). 예전에는 출력을 다 읽은 뒤 {@code waitFor()} 를
 * 무기한 기다려, 빌드가 멈추면 워커 스레드가 영원히 묶였다.
 *
 * <ul>
 *   <li>출력은 별도 스레드가 읽는다 — 파이프가 차서 프로세스가 멈추는 것과 기다리는 것을 떼어 놓는다.
 *       남기는 것은 끝부분(오류 요약용)뿐이라 출력이 커도 메모리가 늘지 않는다.</li>
 *   <li>시간을 넘기면 자식 프로세스까지 강제 종료한다. {@code onTimeout} 은 그 뒤에 부른다 — 프로세스 밖에
 *       남는 자원(예: 데몬이 띄운 빌드 컨테이너)을 정리하는 자리다.</li>
 * </ul>
 */
final class BoundedProcess {

    /** 남기는 출력 끝부분의 크기. {@code BackendSourceClone.tail} 이 다시 줄인다. */
    static final int TAIL_BYTES = 64 * 1024;
    private static final Duration KILL_GRACE = Duration.ofSeconds(10);
    private static final Duration READER_JOIN = Duration.ofSeconds(5);

    private BoundedProcess() {
    }

    /**
     * @return 프로세스의 종료 코드와 출력 끝부분
     * @throws BackendBuildException 시간을 넘겼거나 기다리는 중 중단됐다
     */
    static Result await(Process process, Duration timeout, String what, Runnable onTimeout) {
        TailReader reader = new TailReader(process.getInputStream());
        Thread thread = new Thread(reader, "build-output-" + process.pid());
        thread.setDaemon(true);
        thread.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                try {
                    onTimeout.run();
                } catch (RuntimeException ignored) {
                    // 정리는 best-effort — 시간 초과 자체를 가리지 않는다
                }
                throw new BackendBuildException(what + " 시간 초과(" + describe(timeout) + ") — 중단했습니다");
            }
            thread.join(READER_JOIN.toMillis());
            return new Result(process.exitValue(), reader.tail());
        } catch (InterruptedException e) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new BackendBuildException(what + " 중단");
        }
    }

    /** 자식부터 강제 종료한다 — docker CLI 가 띄운 자식이 남으면 부모만 죽여도 일이 계속된다. */
    static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Duration timeout) {
        long minutes = timeout.toMinutes();
        return minutes > 0 ? minutes + "분" : timeout.toSeconds() + "초";
    }

    record Result(int exitCode, String output) {
    }

    /** 출력을 끝까지 읽으며 마지막 {@link #TAIL_BYTES} 만 남긴다. */
    private static final class TailReader implements Runnable {
        private final InputStream in;
        private final byte[] ring = new byte[TAIL_BYTES];
        private long written;

        private TailReader(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (this) {
                        for (int i = 0; i < n; i++) {
                            ring[(int) (written++ % TAIL_BYTES)] = buffer[i];
                        }
                    }
                }
            } catch (IOException ignored) {
                // 프로세스가 죽으면 파이프가 닫힌다 — 그때까지 읽은 것으로 충분하다
            }
        }

        synchronized String tail() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (written <= TAIL_BYTES) {
                out.write(ring, 0, (int) written);
            } else {
                int start = (int) (written % TAIL_BYTES);
                out.write(ring, start, TAIL_BYTES - start);
                out.write(ring, 0, start);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
