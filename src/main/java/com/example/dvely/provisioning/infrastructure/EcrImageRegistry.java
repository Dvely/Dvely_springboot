package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.RepositoryAlreadyExistsException;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;

/**
 * DOCKER 배포의 이미지 전달을 <b>ECR</b> 로 하는 경로(S3 save/load 의 대안). 프로젝트별 ECR 저장소를
 * 사용자 계정에 멱등하게 만들고, 컨트롤 플레인이 push 할 인증(docker login 자격)을 발급한다. EC2 는
 * 인스턴스 역할로 pull 한다({@code aws ecr get-login-password} → {@code docker pull}).
 *
 * <p>기본 전달 방식은 여전히 S3({@link S3ArtifactStore}) — ECR 은 {@code qeploy.provisioning.ec2.
 * image-transfer=ECR} 로 명시적으로 켤 때만 쓰인다. ECR 은 사용자 BYOC 정책에 ECR 권한 추가가 필요하다
 * (create/push: ecr:CreateRepository·GetAuthorizationToken·Initiate/Upload/CompleteLayerUpload·PutImage·
 * BatchCheckLayerAvailability·DeleteRepository, 그리고 lifecycle: ecr:PutLifecyclePolicy). 자격은 매 호출
 * resolve(다른 프로비저너와 동일 원칙).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EcrImageRegistry {

    private final AwsCredentialsResolver credentialsResolver;

    /**
     * 태그 없는 이미지를 1일 뒤 만료시킨다. 이 경로에 태그 기반 롤백이 없으므로({@code :latest} 하나만
     * 쓴다) 태그 있는 이미지에는 규칙을 두지 않는다. 1일은 직전 이미지를 잠시 남겨 두는 여유다.
     */
    private static final String UNTAGGED_EXPIRY_POLICY = """
            {
              "rules": [
                {
                  "rulePriority": 1,
                  "description": "Expire untagged images after 1 day (Qeploy)",
                  "selection": {
                    "tagStatus": "untagged",
                    "countType": "sinceImagePushed",
                    "countUnit": "days",
                    "countNumber": 1
                  },
                  "action": { "type": "expire" }
                }
              ]
            }
            """;

    /** docker login 자격(레지스트리 호스트 + username/password). 토큰은 단명(기본 12h)이라 배포 때마다 새로 발급. */
    public record EcrAuth(String registry, String username, String password) {
    }

    /** 앱 이미지 저장소 이름. push(save 태그)와 EC2 pull 이 반드시 같아야 하므로 한 곳에서 정한다. */
    public static String repositoryNameFor(Long projectId) {
        return "qeploy-app-" + projectId;
    }

    /** 웹(프론트) 이미지 저장소 이름. */
    public static String webRepositoryNameFor(Long projectId) {
        return "qeploy-web-" + projectId;
    }

    /** 레지스트리 호스트: {account}.dkr.ecr.{region}.amazonaws.com. */
    public String registryFor(CloudConnection connection) {
        return connection.getAccountId() + ".dkr.ecr." + connection.getRegion() + ".amazonaws.com";
    }

    /** 앱 이미지 참조: {registry}/{repo}:latest. */
    public String imageRefFor(CloudConnection connection, Long projectId) {
        return registryFor(connection) + "/" + repositoryNameFor(projectId) + ":latest";
    }

    /** 웹 이미지 참조: {registry}/{web-repo}:latest. */
    public String webImageRefFor(CloudConnection connection, Long projectId) {
        return registryFor(connection) + "/" + webRepositoryNameFor(projectId) + ":latest";
    }

    /** 앱 저장소가 없으면 만든다(멱등). */
    public void ensureRepository(CloudConnection connection, Long projectId) {
        ensureRepo(connection, repositoryNameFor(projectId));
    }

    /** 웹 저장소가 없으면 만든다(멱등). */
    public void ensureWebRepository(CloudConnection connection, Long projectId) {
        ensureRepo(connection, webRepositoryNameFor(projectId));
    }

    private void ensureRepo(CloudConnection connection, String repo) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (EcrClient ecr = client(access)) {
            try {
                ecr.createRepository(r -> r.repositoryName(repo));
                log.info("ECR 저장소 생성: repo={}", repo);
            } catch (RepositoryAlreadyExistsException e) {
                // 멱등 — 이미 있다
            }
            applyLifecyclePolicy(ecr, repo);
        }
    }

    /**
     * 태그 없는 이미지를 만료시키는 lifecycle 정책을 건다(#344 9-3).
     *
     * <p>이 경로는 항상 {@code :latest} 로만 push 한다({@link #imageRefFor}). 그래서 배포마다 직전
     * 이미지가 <b>태그를 잃고 그대로 남는다</b> — 정책이 없으면 프로젝트당 이미지가 무한히 쌓이고,
     * ECR 스토리지는 시간에 비례해 실제 청구서가 된다. 이 구역의 다른 성능 항목과 달리 이것만
     * 방치하면 비용이 계속 늘어난다.</p>
     *
     * <p>새로 만든 저장소만이 아니라 <b>매번</b> 건다. 이 변경 전에 만들어진 저장소가 실제로 쌓이고
     * 있는 것들이고, 그쪽을 건드리지 않으면 고쳐야 할 대상을 비껴간다. {@code putLifecyclePolicy} 는
     * 교체 의미라 반복 호출이 안전하다.</p>
     *
     * <p><b>실패해도 배포를 막지 않는다.</b> 이것은 비용 최적화이고 배포 성공 조건이 아니다. 게다가
     * 자격은 사용자 BYOC 계정이라, 이 변경 전에 정책을 붙인 사용자에게는 {@code ecr:PutLifecyclePolicy}
     * 가 없어 반드시 실패한다 — 그 사용자의 배포를 깨뜨리는 것은 받아들일 수 없다. 대신 무엇을
     * 더해야 하는지 로그로 남긴다.</p>
     */
    private void applyLifecyclePolicy(EcrClient ecr, String repo) {
        try {
            ecr.putLifecyclePolicy(r -> r.repositoryName(repo).lifecyclePolicyText(UNTAGGED_EXPIRY_POLICY));
        } catch (RuntimeException e) {
            // 권한 부족이 가장 흔하다. 조용히 넘기면 비용이 계속 늘어나는 것을 아무도 모른다.
            log.warn("ECR lifecycle 정책 적용 실패 — 태그 없는 이미지가 쌓입니다. "
                            + "사용자 IAM 정책에 ecr:PutLifecyclePolicy 를 추가해야 합니다. repo={} cause={}",
                    repo, e.toString());
        }
    }

    /** docker login 자격을 발급받는다. ECR 토큰은 base64("AWS:password") 형태다. */
    public EcrAuth authorize(CloudConnection connection) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (EcrClient ecr = client(access)) {
            List<AuthorizationData> data = ecr.getAuthorizationToken().authorizationData();
            if (data == null || data.isEmpty()) {
                throw new IllegalStateException("ECR 인증 토큰을 받지 못했습니다.");
            }
            return decodeAuthorizationToken(data.get(0).authorizationToken(), registryFor(connection));
        }
    }

    /** 정리용 — 배포 종료 시 앱 저장소를 이미지째 지운다(force). 없으면 무시. */
    public void deleteRepository(CloudConnection connection, Long projectId) {
        deleteRepo(connection, repositoryNameFor(projectId));
    }

    /** 정리용 — 웹 저장소를 이미지째 지운다(force). 없으면 무시. */
    public void deleteWebRepository(CloudConnection connection, Long projectId) {
        deleteRepo(connection, webRepositoryNameFor(projectId));
    }

    private void deleteRepo(CloudConnection connection, String repo) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (EcrClient ecr = client(access)) {
            try {
                ecr.deleteRepository(r -> r.repositoryName(repo).force(true));
                log.info("ECR 저장소 삭제: repo={}", repo);
            } catch (RepositoryNotFoundException e) {
                // 이미 없다
            }
        }
    }

    /**
     * ECR authorizationToken(base64 "AWS:password")을 docker login 자격으로 푼다. 순수 함수 — 단위테스트.
     */
    static EcrAuth decodeAuthorizationToken(String base64Token, String registry) {
        if (base64Token == null || base64Token.isBlank()) {
            throw new IllegalStateException("ECR 인증 토큰이 비어 있습니다.");
        }
        String decoded = new String(Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
        int sep = decoded.indexOf(':');
        if (sep < 0) {
            throw new IllegalStateException("ECR 인증 토큰 형식 오류(‘user:pass’ 아님).");
        }
        return new EcrAuth(registry, decoded.substring(0, sep), decoded.substring(sep + 1));
    }

    /**
     * 테스트 이음새. {@code ensureRepo} 가 <b>새로 만든 저장소와 이미 있던 저장소 모두에</b>
     * lifecycle 을 거는지, 그리고 그 실패가 배포를 막지 않는지를 보려면 EcrClient 를 끼워야 한다.
     */
    EcrClient client(AwsAccess access) {
        return EcrClient.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
