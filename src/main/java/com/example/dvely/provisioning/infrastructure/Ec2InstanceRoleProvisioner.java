package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AddRoleToInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.CreateInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.PutRolePolicyRequest;

/**
 * EC2 인스턴스가 달고 뜰 IAM 역할·인스턴스 프로파일을 사용자 계정에 <b>멱등하게</b> 만든다.
 * 사용자가 IAM 을 손수 만들 필요 없이(비전문가 진입장벽 제거) 우리가 {@code qeploy-instance-*}
 * 이름으로 최소권한으로 생성한다 — 인스턴스는 자기 프로젝트의 SSM 파라미터·S3 아티팩트만 읽는다.
 *
 * <p>같은 프로젝트로 다시 부르면 이미 있는 것을 재사용한다(getRole/getInstanceProfile 로 확인).
 * <b>주의:</b> IAM 은 최종적 일관성이라, 방금 만든 프로파일로 즉시 runInstances 하면 실패할 수 있다 —
 * 호출자(launch)는 이 프로파일명을 받은 뒤 짧게 재시도해야 한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ec2InstanceRoleProvisioner {

    // 역할·프로파일은 경로(path) 없이 이름 접두사 qeploy-instance-* 로만 스코프한다.
    // 경로(/qeploy/)로 만들면 존재 확인용 getRole(name) 이 "아직 없는" 역할을 평면 ARN
    // (role/qeploy-instance-{id}) 으로 권한평가하는데, 정책이 경로 ARN(role/qeploy/*)만 허용하면
    // 그 Get 이 iam:GetRole 거부로 막힌다(실계정 A 검증에서 확인). 이름 접두사 스코프는 생성·조회의
    // ARN 이 항상 평면으로 일치해 이 함정을 없앤다 — 격리 범위는 접두사가 동일하게 보장한다.
    private final AwsCredentialsResolver credentialsResolver;

    /** EC2 가 이 역할을 맡을 수 있게 하는 신뢰 정책. */
    private static final String TRUST_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
            "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

    /**
     * 인스턴스 프로파일을 보장하고 그 이름을 돌려준다. 없으면 역할+인라인 최소권한 정책+프로파일을
     * 만들고, 있으면 그대로 재사용한다.
     */
    public String ensureInstanceProfile(CloudConnection connection, Long projectId, String bucket, boolean ecr) {
        String name = "qeploy-instance-" + projectId;
        AwsAccess access = credentialsResolver.resolve(connection);
        try (IamClient iam = client(access)) {
            ensureRole(iam, name, projectId, bucket, ecr);
            ensureProfileWithRole(iam, name);
            return name;
        }
    }

    private void ensureRole(IamClient iam, String name, Long projectId, String bucket, boolean ecr) {
        boolean exists;
        try {
            iam.getRole(r -> r.roleName(name));
            exists = true;
        } catch (NoSuchEntityException e) {
            exists = false;
        }
        if (!exists) {
            iam.createRole(CreateRoleRequest.builder()
                    .roleName(name)
                    .assumeRolePolicyDocument(TRUST_POLICY)
                    .description("Qeploy backend instance role for project " + projectId)
                    .build());
            log.info("IAM 인스턴스 역할 생성: role={} projectId={}", name, projectId);
        }
        // 인라인 정책은 항상 현재 모드(ecr)에 맞게 재부착한다(PutRolePolicy 는 멱등 덮어쓰기). 기존
        // 역할이라도 전달방식 전환(S3→ECR)이 반영되게 — 안 그러면 예전에 만들어진 역할이 ECR pull
        // 권한 없이 남아 인스턴스의 docker pull 이 조용히 실패하고, 앱이 안 떠 부팅 타임아웃으로만
        // 드러난다(실계정 e2e 2026-09-04 에서 확인). 우리가 소유·관리하는 인라인 정책이라 덮어써도 안전하다.
        iam.putRolePolicy(PutRolePolicyRequest.builder()
                .roleName(name).policyName("qeploy-instance-access")
                .policyDocument(instancePolicy(projectId, bucket, ecr))
                .build());
        log.info("IAM 인스턴스 역할 정책 반영: role={} projectId={} ecr={} (기존={})", name, projectId, ecr, exists);
    }

    /**
     * DOCKER DB EC2 용 인스턴스 프로파일을 보장한다. 백엔드 역할과 달리 이 역할은 <b>준비되면 자기 사설
     * IP 를 SSM 에 self-report(PutParameter)</b> 하는 권한만 갖는다 — 컨트롤 플레인은 사설망의 DB 를 직접
     * 헬스체크할 수 없어 이 신호로 준비를 판단한다. 이름 접두사 qeploy-instance-* 를 유지해 기존 BYOC IAM
     * 스코프(role/qeploy-instance-*) 안에서 만들어진다(새 정책 추가 불필요).
     */
    public String ensureDbWriterInstanceProfile(CloudConnection connection, Long projectId) {
        String name = "qeploy-instance-dbw-" + projectId;
        AwsAccess access = credentialsResolver.resolve(connection);
        try (IamClient iam = client(access)) {
            boolean exists;
            try {
                iam.getRole(r -> r.roleName(name));
                exists = true;
            } catch (NoSuchEntityException e) {
                exists = false;
            }
            if (!exists) {
                iam.createRole(CreateRoleRequest.builder()
                        .roleName(name)
                        .assumeRolePolicyDocument(TRUST_POLICY)
                        .description("Qeploy DB-writer instance role for project " + projectId)
                        .build());
                log.info("IAM DB-writer 역할 생성: role={} projectId={}", name, projectId);
            }
            iam.putRolePolicy(PutRolePolicyRequest.builder()
                    .roleName(name).policyName("qeploy-db-writer")
                    .policyDocument(dbWriterPolicy(projectId))
                    .build());
            ensureProfileWithRole(iam, name);
            return name;
        }
    }

    /** DB EC2 준비 self-report 전용 — 자기 프로젝트 경로에 PutParameter 만. */
    private String dbWriterPolicy(Long projectId) {
        return """
                {"Version":"2012-10-17","Statement":[\
                {"Effect":"Allow","Action":["ssm:PutParameter"],\
                "Resource":"arn:aws:ssm:*:*:parameter/qeploy/%d/*"}]}""".formatted(projectId);
    }

    private void ensureProfileWithRole(IamClient iam, String name) {
        try {
            GetInstanceProfileResponse profile = iam.getInstanceProfile(
                    GetInstanceProfileRequest.builder().instanceProfileName(name).build());
            if (profile.instanceProfile().roles().isEmpty()) {
                iam.addRoleToInstanceProfile(AddRoleToInstanceProfileRequest.builder()
                        .instanceProfileName(name).roleName(name).build());
            }
            return;   // 이미 있다(역할까지 붙어 있으면 그대로)
        } catch (NoSuchEntityException e) {
            // 아래에서 만든다
        }
        iam.createInstanceProfile(CreateInstanceProfileRequest.builder()
                .instanceProfileName(name).build());
        iam.addRoleToInstanceProfile(AddRoleToInstanceProfileRequest.builder()
                .instanceProfileName(name).roleName(name).build());
        log.info("IAM 인스턴스 프로파일 생성: profile={}", name);
    }

    /**
     * 인스턴스가 받을 최소권한: 자기 프로젝트의 SSM 파라미터 읽기 + 자기 S3 아티팩트 읽기. ECR 전달을
     * 켜면(ecr=true) 자기 프로젝트 ECR 저장소 pull 권한을 더한다({@code GetAuthorizationToken} 은 계정
     * 레벨이라 Resource *, 나머지 pull 은 저장소 ARN 으로 좁힌다).
     */
    private String instancePolicy(Long projectId, String bucket, boolean ecr) {
        // qeploy-*-{projectId} 로 이 프로젝트의 app·web 저장소를 함께 커버(여전히 프로젝트 스코프).
        String ecrStatements = ecr ? """
                ,{"Effect":"Allow","Action":["ecr:GetAuthorizationToken"],"Resource":"*"},\
                {"Effect":"Allow","Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer",\
                "ecr:BatchCheckLayerAvailability"],\
                "Resource":"arn:aws:ecr:*:*:repository/qeploy-*-%d"}""".formatted(projectId) : "";
        // SSM core — 인스턴스를 SSM managed instance 로 등록해 Run Command(로그 조회)를 받게 한다. AL2023 은
        // 에이전트 기본 탑재라 설치는 불필요하고 이 권한만 있으면 된다. 리소스 스코프 불가라 Resource:"*".
        // (관리형 정책 AttachRolePolicy 는 BYOC 에 없으므로 인라인으로 넣는다 — putRolePolicy 로 멱등 갱신.)
        String ssmCore = """
                ,{"Effect":"Allow","Action":["ssm:UpdateInstanceInformation",\
                "ssmmessages:CreateControlChannel","ssmmessages:CreateDataChannel",\
                "ssmmessages:OpenControlChannel","ssmmessages:OpenDataChannel",\
                "ec2messages:AcknowledgeMessage","ec2messages:DeleteMessage","ec2messages:FailMessage",\
                "ec2messages:GetEndpoint","ec2messages:GetMessages","ec2messages:SendReply"],"Resource":"*"}""";
        return """
                {"Version":"2012-10-17","Statement":[\
                {"Effect":"Allow","Action":["ssm:GetParameter","ssm:GetParameters","ssm:GetParametersByPath"],\
                "Resource":"arn:aws:ssm:*:*:parameter/qeploy/%d/*"},\
                {"Effect":"Allow","Action":["s3:GetObject"],\
                "Resource":"arn:aws:s3:::%s/%d/*"}%s%s]}"""
                .formatted(projectId, bucket, projectId, ssmCore, ecrStatements);
    }

    private IamClient client(AwsAccess access) {
        // IAM 은 글로벌 서비스다 — 사용자 리전(예: ap-northeast-2)을 주면 엔드포인트가 없어 실패한다.
        return IamClient.builder()
                .region(Region.AWS_GLOBAL)
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
