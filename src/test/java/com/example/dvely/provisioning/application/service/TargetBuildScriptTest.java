package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 대상 EC2 빌드(#556)의 부팅 스크립트. 문자열 형태를 고정하고, 빌드 구간은 스텁 명령으로 실제 bash 에서 돌려
 * 보고 순서와 실패 시 중단을 확인한다 — 문자열만 보면 셸에서 어떻게 도는지는 알 수 없다.
 */
class TargetBuildScriptTest {

    private static final BackendDeployRunner.TargetBuild APP_ONLY = new BackendDeployRunner.TargetBuild(
            "ap-northeast-2", 5L, "qeploy-artifacts-x", "5/app-context.tar", "qeploy-app-5:latest",
            null, null, 1800);
    private static final BackendDeployRunner.TargetBuild APP_AND_WEB = new BackendDeployRunner.TargetBuild(
            "ap-northeast-2", 5L, "qeploy-artifacts-x", "5/app-context.tar", "qeploy-app-5:latest",
            "5/web-context.tar", "qeploy-web-5:latest", 1800);

    @Test
    void buildsOnTheInstanceBeforeLoadingEnvAndRunningTheSameImageTag() {
        String script = BackendDeployRunner.targetUserDataScript(APP_ONLY, 8080, "");

        assertThat(script)
                .contains("dnf install -y python3 docker")
                .contains("aws s3 cp \"s3://qeploy-artifacts-x/$1\" /opt/app/context.tar")
                .contains("build_image 5/app-context.tar /opt/app/build/app qeploy-app-5:latest && true")
                .contains("timeout 1800 docker build -t \"$3\" \"$2\"")
                .contains("--name \"/qeploy/build-status/5/$IID\"")
                .contains("docker run -d --name qeploy-app --restart unless-stopped -p 8080:8080 "
                        + "--env-file /opt/app/app.env qeploy-app-5:latest")
                .doesNotContain("docker load");
        // 빌드가 성공해야 env 를 내리고 앱을 띄운다
        assertThat(script.indexOf("report_build BUILT")).isLessThan(script.indexOf("get-parameters-by-path"));
        assertThat(script.indexOf("report_build BUILDING")).isLessThan(script.indexOf("if ! {"));
    }

    @Test
    void composeBuildsAppAndWebThenBringsThemUpFromLocalImages() {
        String script = BackendDeployRunner.targetComposeUserDataScript(APP_AND_WEB, DatabaseEngine.MYSQL, 8080, "");

        assertThat(script)
                .contains("build_image 5/app-context.tar /opt/app/build/app qeploy-app-5:latest && "
                        + "build_image 5/web-context.tar /opt/app/build/web qeploy-web-5:latest && true")
                .contains("image: qeploy-app-5:latest")
                .contains("image: qeploy-web-5:latest")
                .contains("image: mysql:8.0")
                .doesNotContain("docker load")
                .doesNotContain("docker pull");
        assertThat(script.indexOf("report_build BUILT")).isLessThan(script.indexOf("docker compose"));
    }

    @Test
    void aSuccessfulBuildReportsBuildingThenBuiltAndGoesOn(@TempDir Path dir) throws Exception {
        Run run = runBuildSection(dir, false);

        assertThat(run.exitCode).isZero();
        assertThat(run.reports).containsExactly("BUILDING", "BUILT");
        assertThat(run.names).allMatch(name -> name.equals("/qeploy/build-status/5/i-abc"));
        assertThat(Files.exists(dir.resolve("started"))).isTrue();
    }

    @Test
    void aFailedBuildReportsTheLogTailAndStopsBeforeTheAppStarts(@TempDir Path dir) throws Exception {
        Run run = runBuildSection(dir, true);

        assertThat(run.exitCode).isEqualTo(1);
        assertThat(run.reports).hasSize(2);
        assertThat(run.reports.get(0)).isEqualTo("BUILDING");
        assertThat(run.reports.get(1)).startsWith("BUILD_FAILED\n").contains("npm ERR! missing script: build");
        assertThat(Files.exists(dir.resolve("started"))).isFalse();
    }

    private record Run(int exitCode, List<String> reports, List<String> names) {
    }

    private Run runBuildSection(Path dir, boolean failBuild) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "bash 가 있어야 한다");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path opt = Files.createDirectories(dir.resolve("opt"));
        Path logs = Files.createDirectories(dir.resolve("log"));
        Path reports = Files.createDirectories(dir.resolve("reports"));
        Path context = dir.resolve("context.tar");
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("Dockerfile"), "FROM scratch\n");
        assertThat(new ProcessBuilder("tar", "-cf", context.toString(), "-C", src.toString(), ".")
                .start().waitFor()).isZero();

        stub(bin, "curl", """
                case "$*" in *api/token*) echo tok ;; *instance-id*) echo i-abc ;; esac
                """);
        stub(bin, "timeout", "shift; exec \"$@\"\n");
        stub(bin, "aws", """
                if [ "$1 $2" = "s3 cp" ]; then cp "$CONTEXT_TAR" "$4"; exit 0; fi
                if [ "$1 $2" = "ssm put-parameter" ]; then
                  while [ $# -gt 0 ]; do
                    case "$1" in --name) name="$2"; shift ;; --value) value="$2"; shift ;; esac
                    shift
                  done
                  n=$(ls "$REPORTS" | grep -c '^value-' || true)
                  printf '%s' "$value" > "$REPORTS/value-$n"
                  printf '%s\\n' "$name" >> "$REPORTS/names"
                fi
                """);
        stub(bin, "docker", """
                if [ "$1" = "build" ]; then
                  echo "Step 1/3 : FROM node:20"
                  if [ -n "$FAIL_BUILD" ]; then echo "npm ERR! missing script: build"; exit 1; fi
                  echo "Successfully tagged $3"
                fi
                """);

        String section = BackendDeployRunner.targetBuildSection(APP_ONLY)
                .replace("/opt/app", opt.toString())
                .replace("/var/log", logs.toString());
        Path script = dir.resolve("script.sh");
        Files.writeString(script, "#!/bin/bash\nset -e\n" + section + "echo started > \"$STARTED\"\n");

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", script.toString()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.get("PATH"));
        env.put("REPORTS", reports.toString());
        env.put("CONTEXT_TAR", context.toString());
        env.put("STARTED", dir.resolve("started").toString());
        if (failBuild) {
            env.put("FAIL_BUILD", "1");
        }
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();

        List<String> values = new ArrayList<>();
        for (int i = 0; Files.exists(reports.resolve("value-" + i)); i++) {
            values.add(Files.readString(reports.resolve("value-" + i), StandardCharsets.UTF_8));
        }
        List<String> names = Files.exists(reports.resolve("names"))
                ? Files.readAllLines(reports.resolve("names")) : List.of();
        return new Run(process.exitValue(), values, names);
    }

    private static void stub(Path bin, String name, String body) throws IOException {
        Path file = bin.resolve(name);
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
