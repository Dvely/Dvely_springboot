package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** 이미지 빌드 프로세스 대기(#507) — 실제 프로세스로 확인한다. */
@DisabledOnOs(OS.WINDOWS)
class BoundedProcessTest {

    @Test
    void aProcessThatNeverEndsIsKilledWithItsChildrenAfterTheTimeout() throws Exception {
        // 자식(sleep)을 띄우고 기다리는 셸 — docker CLI 가 자식을 둔 모양과 같다
        Process process = new ProcessBuilder("sh", "-c", "sleep 300 & echo $!; wait").redirectErrorStream(true).start();
        AtomicBoolean cleaned = new AtomicBoolean();
        long started = System.nanoTime();

        assertThatThrownBy(() -> BoundedProcess.await(process, Duration.ofSeconds(1), "이미지 빌드", () -> cleaned.set(true)))
                .isInstanceOf(BackendBuildException.class)
                .hasMessageContaining("이미지 빌드 시간 초과(1초)");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        assertThat(process.isAlive()).isFalse();
        assertThat(process.descendants().filter(ProcessHandle::isAlive).count()).isZero();
        assertThat(cleaned).isTrue();
    }

    @Test
    void theChildIsDeadToo() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 300 & echo $!; wait").redirectErrorStream(true).start();
        // 자식 pid 를 먼저 읽는다(첫 줄)
        String first = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream())).readLine();
        long child = Long.parseLong(first.trim());
        assertThat(ProcessHandle.of(child)).isPresent();

        assertThatThrownBy(() -> BoundedProcess.await(process, Duration.ofSeconds(1), "빌드", () -> { }))
                .isInstanceOf(BackendBuildException.class);

        Thread.sleep(200);
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void anInterruptedWaitAlsoKillsTheProcessAndRunsTheCleanUp() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 300 & echo $!; wait").redirectErrorStream(true).start();
        AtomicBoolean cleaned = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean interruptKept = new java.util.concurrent.atomic.AtomicBoolean();
        Thread waiter = new Thread(() -> {
            try {
                BoundedProcess.await(process, Duration.ofMinutes(5), "이미지 빌드", () -> cleaned.set(true));
            } catch (Throwable t) {
                thrown.set(t);
                interruptKept.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        Thread.sleep(300);

        waiter.interrupt();
        waiter.join(20_000);

        assertThat(thrown.get()).isInstanceOf(BackendBuildException.class).hasMessageContaining("이미지 빌드 중단");
        assertThat(process.isAlive()).isFalse();
        assertThat(cleaned).isTrue();          // 데몬 쪽 자원 정리가 중단 경로에서도 돈다
        assertThat(interruptKept).isTrue();    // 인터럽트 표시를 되살린다
    }

    @Test
    void largeOutputDoesNotBlockAndOnlyTheTailIsKept() throws Exception {
        // 파이프 버퍼(수십 KB)를 훨씬 넘는 출력. 읽는 쪽이 없으면 프로세스가 쓰다 멈춘다
        Process process = new ProcessBuilder("sh", "-c", "i=0; while [ $i -lt 20000 ]; do echo line-$i; i=$((i+1)); done")
                .redirectErrorStream(true).start();

        BoundedProcess.Result result = BoundedProcess.await(process, Duration.ofSeconds(30), "출력", () -> { });

        assertThat(result.exitCode()).isZero();
        assertThat(result.output().length()).isLessThanOrEqualTo(BoundedProcess.TAIL_BYTES);
        assertThat(result.output()).endsWith("line-19999\n").doesNotContain("line-0\n");
    }

    @Test
    void aNormalExitReturnsItsCodeAndOutput() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "echo boom; exit 3").redirectErrorStream(true).start();
        AtomicBoolean cleaned = new AtomicBoolean();

        BoundedProcess.Result result = BoundedProcess.await(process, Duration.ofSeconds(10), "빌드", () -> cleaned.set(true));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.output()).isEqualTo("boom\n");
        assertThat(cleaned).isFalse();   // 정리는 시간 초과 때만
    }
}
