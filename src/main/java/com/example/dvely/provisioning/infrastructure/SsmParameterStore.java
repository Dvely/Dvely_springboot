package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DeleteParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;

/**
 * EC2 앱에 줄 비밀(DB 접속정보·환경변수)을 사용자 계정의 SSM Parameter Store 에 SecureString 으로
 * 넣는다. user-data·로그·AMI 어디에도 평문을 두지 않기 위함이다 — 인스턴스는 부팅 때 자기 IAM
 * 역할로 자기 경로({@code /qeploy/{projectId}/*})의 파라미터만 읽는다.
 *
 * <p>SecureString 은 계정의 AWS 관리형 KMS 키(aws/ssm)로 암호화된다(무료). 자격은 매 호출 resolve.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsmParameterStore {

    private final AwsCredentialsResolver credentialsResolver;

    /** 프로젝트 파라미터 경로 접두사. IAM 정책이 이 경로로 인스턴스 접근을 좁힌다. */
    public String pathPrefixFor(Long projectId) {
        return "/qeploy/" + projectId + "/";
    }

    /**
     * 프로젝트의 env 전체를 SecureString 으로 올린다(덮어쓰기). key 는 POSIX env 이름 그대로,
     * 파라미터명은 {@code /qeploy/{projectId}/{KEY}} 가 된다.
     */
    public void putAll(CloudConnection connection, Long projectId, Map<String, String> env) {
        AwsAccess access = credentialsResolver.resolve(connection);
        String prefix = pathPrefixFor(projectId);
        try (SsmClient ssm = client(access)) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                ssm.putParameter(PutParameterRequest.builder()
                        .name(prefix + e.getKey())
                        .value(e.getValue())
                        .type(ParameterType.SECURE_STRING)
                        .overwrite(true)
                        .build());
            }
            log.info("SSM 파라미터 업로드: projectId={} count={}", projectId, env.size());
        }
    }

    /** 정리용 — 배포 종료 시 이 프로젝트 경로의 파라미터를 모두 지운다. */
    public void deleteAllForProject(CloudConnection connection, Long projectId) {
        AwsAccess access = credentialsResolver.resolve(connection);
        String prefix = pathPrefixFor(projectId);
        try (SsmClient ssm = client(access)) {
            List<String> names = listNames(ssm, prefix);
            for (String name : names) {
                try {
                    ssm.deleteParameter(DeleteParameterRequest.builder().name(name).build());
                } catch (ParameterNotFoundException ignored) {
                    // 이미 없음 — 무시
                }
            }
            log.info("SSM 파라미터 삭제: projectId={} count={}", projectId, names.size());
        }
    }

    private List<String> listNames(SsmClient ssm, String prefix) {
        List<String> names = new ArrayList<>();
        String token = null;
        do {
            GetParametersByPathResponse resp = ssm.getParametersByPath(GetParametersByPathRequest.builder()
                    .path(prefix).recursive(true).nextToken(token).build());
            resp.parameters().forEach(p -> names.add(p.name()));
            token = resp.nextToken();
        } while (token != null);
        return names;
    }

    private SsmClient client(AwsAccess access) {
        return SsmClient.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
