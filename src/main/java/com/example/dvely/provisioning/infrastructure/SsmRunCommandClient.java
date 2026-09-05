package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.InvocationDoesNotExistException;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;

/**
 * 실행 중 EC2 인스턴스에서 셸 명령을 돌려 그 출력을 가져온다(SSM Run Command, AWS-RunShellScript). 로그
 * 조회에 쓴다 — SSH 가 막혀 있고(SG 는 앱 포트만) 로그를 CloudWatch 로 실어 나르지도 않아, SSM 이 살아있는
 * 인스턴스의 로그에 닿는 사실상 유일한 경로다.
 *
 * <p>인스턴스가 SSM managed instance 로 등록돼 있어야 한다(인스턴스 역할에 SSM core 권한 필요 — AL2023 은
 * 에이전트 기본 탑재). 자격은 매 호출 resolve. SendCommand 는 비동기라 CommandId 를 받아 GetCommandInvocation
 * 을 Success/실패까지 폴링한다. 인라인 출력은 SSM 상 ~24000자에서 잘리므로 "최근 N 줄" 용도로 쓴다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsmRunCommandClient {

    /**
     * 부트 로그(cloud-init) 최근 200줄을 뽑는 셸 명령. 로그 조회(ServerLogQueryService 의 BOOT 소스)와
     * 부트 타임아웃 종료 직전 진단 보존(ProvisionedServerStatusWorker)이 같은 문자열을 써야 어긋나지 않아
     * 여기 한 곳에 둔다.
     */
    public static final String BOOT_LOG_TAIL =
            "sudo tail -n 200 /var/log/cloud-init-output.log 2>/dev/null || echo '부트 로그 없음'";

    private static final int MAX_POLLS = 20;
    private static final long POLL_INTERVAL_MS = 700;
    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    // 더 진행하지 않는 종료 상태(Success 는 별도 처리).
    private static final Set<String> TERMINAL_FAILURES = Set.of(
            "Cancelled", "Failed", "TimedOut", "Cancelling");

    private final AwsCredentialsResolver credentialsResolver;

    /**
     * 인스턴스에서 셸 명령을 실행하고 표준출력을 돌려준다. 실패 상태면 표준에러를 담아 예외를 던진다. 인스턴스가
     * SSM 에 아직 등록 전이면(InvalidInstanceId/InvocationDoesNotExist) 잠시 재시도한다.
     */
    public String runShellCommand(CloudConnection connection, String instanceId, String command) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (SsmClient ssm = client(access)) {
            String commandId = ssm.sendCommand(SendCommandRequest.builder()
                    .documentName("AWS-RunShellScript")
                    .instanceIds(instanceId)
                    .parameters(Map.of("commands", List.of(command)))
                    .timeoutSeconds(COMMAND_TIMEOUT_SECONDS)
                    .build()).command().commandId();

            for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
                sleep(POLL_INTERVAL_MS);
                GetCommandInvocationResponse invocation;
                try {
                    invocation = ssm.getCommandInvocation(GetCommandInvocationRequest.builder()
                            .commandId(commandId).instanceId(instanceId).build());
                } catch (InvocationDoesNotExistException notYet) {
                    continue;   // 인스턴스가 명령을 아직 못 받음 — 다음 폴에서 다시 본다
                }
                String status = invocation.statusAsString();
                if ("Success".equals(status)) {
                    return invocation.standardOutputContent();
                }
                if (TERMINAL_FAILURES.contains(status)) {
                    throw new IllegalStateException("명령 실패(" + status + "): "
                            + tail(invocation.standardErrorContent()));
                }
                // Pending/InProgress/Delayed — 계속 폴링
            }
            throw new IllegalStateException("명령 응답 시간 초과(instanceId=" + instanceId + ")");
        }
    }

    private static String tail(String s) {
        if (s == null || s.isBlank()) {
            return "(출력 없음)";
        }
        return s.length() <= 500 ? s : s.substring(s.length() - 500);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private SsmClient client(AwsAccess access) {
        return SsmClient.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
