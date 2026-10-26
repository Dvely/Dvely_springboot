package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 인증 git 호출 하드닝을 <b>실제 git</b> 으로 돌려 확인한다. 문자열 조립만 보는 테스트로는 셸 인용·
 * git 의 helper 호출·설정 우선순위가 맞는지 알 수 없다. 개발 PC 의 전역·시스템 설정(예: macOS 의
 * osxkeychain helper)이 섞이지 않게 HOME 을 비우고 시스템 설정을 끈다 — 컨테이너(alpine)와 같은 조건이다.
 */
class ContainerGitCredentialsHardeningTest {

    private static final String USER = "octo";
    private static final String TOKEN = "fake-token-for-test";

    private final ContainerGitCredentials credentials = new ContainerGitCredentials(mock(DockerContainerService.class));

    @TempDir Path home;
    @TempDir Path repo;

    @BeforeEach
    void initRepository() throws Exception {
        run(repo, "git init -q && git config user.email t@t && git config user.name t"
                + " && git remote add origin https://github.com/octo/app.git");
    }

    @Test
    void theHelperAnswersGithubOverHttps() throws Exception {
        Result result = run(repo, credentials.authed("git credential fill"), "protocol=https\nhost=github.com\n\n");

        assertThat(result.output()).contains("password=" + TOKEN);
    }

    @Test
    void theHelperStaysSilentForAnyOtherHost() throws Exception {
        Result result = run(repo, credentials.authed("git credential fill"), "protocol=https\nhost=evil.example\n\n");

        assertThat(result.output()).doesNotContain(TOKEN);
    }

    @Test
    void theHelperStaysSilentOverPlainHttp() throws Exception {
        Result result = run(repo, credentials.authed("git credential fill"), "protocol=http\nhost=github.com\n\n");

        assertThat(result.output()).doesNotContain(TOKEN);
    }

    @Test
    void aCleanRepositoryRunsTheCommand() throws Exception {
        Result result = run(repo, credentials.authed("git rev-parse --is-inside-work-tree"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("true");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "url.https://elsewhere.example/.insteadOf https://github.com/",
            "remote.origin.pushurl https://elsewhere.example/app.git",
            "credential.helper store",
            "http.proxy http://127.0.0.1:9",
            "core.hooksPath /tmp/hooks",
            "core.fsmonitor true",
            "filter.x.smudge cat",
            "include.path /tmp/other.gitconfig"
    })
    void anUnsafeRepositoryConfigStopsTheAuthenticatedCall(String keyAndValue) throws Exception {
        run(repo, "git config " + keyAndValue);

        Result result = run(repo, credentials.authed("git rev-parse --is-inside-work-tree"));

        assertThat(result.exitCode()).isEqualTo(ContainerGitCredentials.UNSAFE_CONFIG_EXIT);
        assertThat(result.output()).doesNotContain("true");
    }

    @Test
    void anUnsafeGlobalConfigStopsItTooEvenOutsideARepository() throws Exception {
        // clone 은 저장소 밖에서 돈다 — 전역 설정만으로도 막혀야 한다.
        run(repo, "git config --global url.https://elsewhere.example/.insteadOf https://github.com/");

        Result result = run(home, credentials.authed("GIT_TERMINAL_PROMPT=0 git --version"));

        assertThat(result.exitCode()).isEqualTo(ContainerGitCredentials.UNSAFE_CONFIG_EXIT);
    }

    @Test
    void hooksDoNotRunDuringAnAuthenticatedCall() throws Exception {
        Path hooks = repo.resolve(".git/hooks");
        Path marker = home.resolve("hook-ran");
        Files.writeString(hooks.resolve("pre-push"), "#!/bin/sh\ntouch " + marker + "\nexit 1\n");
        hooks.resolve("pre-push").toFile().setExecutable(true);
        run(repo, "git commit -q --allow-empty -m init");
        Path bare = home.resolve("remote.git");
        run(home, "git init -q --bare " + bare);

        Result result = run(repo, credentials.authed("git push -q " + bare + " HEAD:refs/heads/preview"));

        assertThat(result.exitCode()).isZero();
        assertThat(marker).doesNotExist();
    }

    @Test
    void anEnvironmentPrefixStaysAttachedToGit() {
        // 검사가 GIT_TERMINAL_PROMPT=0 과 git 사이에 끼면 그 환경 지정이 검사에 걸리고 git 에는 안 걸린다.
        String command = credentials.authed("GIT_TERMINAL_PROMPT=0 git clone https://github.com/o/r.git /app");

        assertThat(command).contains("&& GIT_TERMINAL_PROMPT=0 /usr/bin/git -c core.hooksPath=/dev/null");
        assertThat(command.indexOf("config --get-regexp")).isLessThan(command.indexOf("GIT_TERMINAL_PROMPT"));
    }

    @Test
    void aGitPlantedEarlierOnThePathIsNeverRun() throws Exception {
        // 쓰기 가능한 디렉터리가 PATH 앞에 있어도, 인증 호출과 그 뒤의 git 은 절대 경로의 git 만 쓴다.
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path marker = home.resolve("fake-git-ran");
        Path fake = bin.resolve("git");
        Files.writeString(fake, "#!/bin/sh\ntouch " + marker + "\nexit 0\n");
        fake.toFile().setExecutable(true);

        Result result = run(repo, credentials.authed("git rev-parse --is-inside-work-tree && git rev-parse --git-dir"),
                "", Map.of("PATH", bin + ":" + System.getenv("PATH")));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("true", ".git");
        assertThat(marker).doesNotExist();
    }

    @Test
    void anExecPathTheUserCanWriteStopsTheAuthenticatedCall() throws Exception {
        // git-remote-https 같은 하위 프로그램을 찾는 자리가 root 소유가 아니면 토큰을 넘기지 않는다.
        Path writable = Files.createDirectories(home.resolve("libexec"));

        Result result = run(repo, credentials.authed("git rev-parse --is-inside-work-tree"),
                "", Map.of("GIT_EXEC_PATH", writable.toString()));

        assertThat(result.exitCode()).isEqualTo(ContainerGitCredentials.UNSAFE_CONFIG_EXIT);
    }

    private Result run(Path dir, String command) throws Exception {
        return run(dir, command, "");
    }

    private Result run(Path dir, String command, String stdin) throws IOException, InterruptedException {
        return run(dir, command, stdin, Map.of());
    }

    private Result run(Path dir, String command, String stdin, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        for (String pair : credentials.env(USER, TOKEN)) {
            String[] kv = pair.split("=", 2);
            env.put(kv[0], kv[1]);
        }
        env.putAll(extraEnv);
        Process process = builder.start();
        process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("timeout: " + command);
        }
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {
    }
}
