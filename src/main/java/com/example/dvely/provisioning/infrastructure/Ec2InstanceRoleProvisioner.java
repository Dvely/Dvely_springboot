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
        try {
            iam.getRole(r -> r.roleName(name));
            return;   // 이미 있다 — 정책은 처음 만들 때 붙였으므로 재부착 안 함(전달방식 바뀌면 새 프로젝트부터 반영)
        } catch (NoSuchEntityException e) {
            // 아래에서 만든다
        }
        iam.createRole(CreateRoleRequest.builder()
                .roleName(name)
                .assumeRolePolicyDocument(TRUST_POLICY)
                .description("Qeploy backend instance role for project " + projectId)
                .build());
        iam.putRolePolicy(PutRolePolicyRequest.builder()
                .roleName(name).policyName("qeploy-instance-access")
                .policyDocument(instancePolicy(projectId, bucket, ecr))
                .build());
        log.info("IAM 인스턴스 역할 생성: role={} projectId={} ecr={}", name, projectId, ecr);
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
        String ecrStatements = ecr ? """
                ,{"Effect":"Allow","Action":["ecr:GetAuthorizationToken"],"Resource":"*"},\
                {"Effect":"Allow","Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer",\
                "ecr:BatchCheckLayerAvailability"],\
                "Resource":"arn:aws:ecr:*:*:repository/qeploy-app-%d"}""".formatted(projectId) : "";
        return """
                {"Version":"2012-10-17","Statement":[\
                {"Effect":"Allow","Action":["ssm:GetParameter","ssm:GetParameters","ssm:GetParametersByPath"],\
                "Resource":"arn:aws:ssm:*:*:parameter/qeploy/%d/*"},\
                {"Effect":"Allow","Action":["s3:GetObject"],\
                "Resource":"arn:aws:s3:::%s/%d/*"}%s]}""".formatted(projectId, bucket, projectId, ecrStatements);
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
