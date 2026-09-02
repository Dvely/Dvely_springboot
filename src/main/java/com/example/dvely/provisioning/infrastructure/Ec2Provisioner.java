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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataEndpointState;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

/**
 * EC2 백엔드 서버를 사용자 AWS 계정(BYOC)에 프로비저닝한다. RDS 와 마찬가지로 <b>비동기</b>다 —
 * runInstances 는 즉시 인스턴스 ID 를 주지만 running·헬스체크 통과까지는 수십 초~수 분 걸리므로,
 * 승인 핸들러가 {@link #launch}로 시작하고 상태 워커가 {@link #describe}로 폴링한다.
 *
 * <p>이 클래스는 순수 EC2 기계장치만 담는다 — user-data 에 무엇을 담을지(S3 pull·SSM fetch·
 * java -jar), 어떤 SG·IAM 프로파일을 쓸지는 호출자가 {@link LaunchSpec}로 완성해 넘긴다. 보안 기본:
 * IMDSv2 강제(SSRF 로 자격·user-data 탈취 차단). 자격은 {@link AwsCredentialsResolver}로 매
 * 호출마다 새로 얻는다(assume-role 세션이 짧다 — RdsProvisioner 와 동일 원칙).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ec2Provisioner {

    private final AwsCredentialsResolver credentialsResolver;

    /**
     * 인스턴스 생성에 필요한 완성된 스펙. userData 는 base64 가 아닌 평문으로 받는다(SDK 가 인코딩).
     * 비밀은 절대 여기 담지 않는다 — user-data 는 IMDS 로 조회 가능하므로, 비밀은 SSM 에 두고
     * userData 안에서는 "SSM 에서 이름으로 가져오라"는 명령만 담는다.
     */
    public record LaunchSpec(
            String instanceType,           // 예: "t3.micro" (설정 가능, 기본 micro)
            String imageId,                // AMI (리전별 Amazon Linux 2023 등)
            String userData,               // 평문 부트스트랩 스크립트 (비밀 금지)
            String securityGroupId,
            String subnetId,               // null 이면 기본 서브넷
            String iamInstanceProfileName, // 인스턴스가 SSM·S3 를 읽을 역할
            String nameTag
    ) {}

    /** 인스턴스 상태 스냅샷. publicHost 는 running 이 되기 전에는 null 일 수 있다. */
    public record Ec2InstanceStatus(String state, String publicHost) {}

    /** 인스턴스 생성을 시작한다(즉시 인스턴스 ID 반환). running/헬스체크는 상태 워커가 확인한다. */
    public String launch(CloudConnection connection, LaunchSpec spec) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            RunInstancesRequest.Builder req = RunInstancesRequest.builder()
                    .imageId(spec.imageId())
                    .instanceType(spec.instanceType())
                    .minCount(1)
                    .maxCount(1)
                    .securityGroupIds(spec.securityGroupId())
                    // RunInstances 의 userData 는 base64 인코딩된 문자열을 요구한다(SDK 가 자동으로
                    // 안 해준다). 직접 인코딩해 넣는다.
                    .userData(Base64.getEncoder().encodeToString(
                            spec.userData().getBytes(StandardCharsets.UTF_8)))
                    .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                            .name(spec.iamInstanceProfileName()).build())
                    // IMDSv2 강제 — 토큰 없는 IMDS 접근 차단(SSRF 완화). 기본값보다 강하게 명시.
                    .metadataOptions(InstanceMetadataOptionsRequest.builder()
                            .httpTokens(HttpTokensState.REQUIRED)
                            .httpEndpoint(InstanceMetadataEndpointState.ENABLED)
                            .build())
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key("Name").value(spec.nameTag()).build(),
                                  Tag.builder().key("managed-by").value("qeploy").build())
                            .build());
            if (spec.subnetId() != null && !spec.subnetId().isBlank()) {
                req.subnetId(spec.subnetId());
            }
            String instanceId = ec2.runInstances(req.build()).instances().get(0).instanceId();
            log.info("EC2 인스턴스 생성 시작: instanceId={} type={} name={}",
                    instanceId, spec.instanceType(), spec.nameTag());
            return instanceId;
        }
    }

    /** 인스턴스 상태를 조회한다. running 이면 publicDnsName 이 채워져 host 가 나온다. */
    public Ec2InstanceStatus describe(CloudConnection connection, String instanceId) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            var reservations = ec2.describeInstances(DescribeInstancesRequest.builder()
                    .instanceIds(instanceId).build()).reservations();
            Instance instance = reservations.stream()
                    .map(Reservation::instances).flatMap(List::stream)
                    .findFirst().orElse(null);
            if (instance == null) {
                return new Ec2InstanceStatus("terminated", null);
            }
            String host = (instance.publicDnsName() == null || instance.publicDnsName().isBlank())
                    ? instance.publicIpAddress() : instance.publicDnsName();
            return new Ec2InstanceStatus(instance.state().nameAsString(), host);
        } catch (Ec2Exception e) {
            // InvalidInstanceID.NotFound 등 — 이미 없는 것으로 본다.
            if (e.awsErrorDetails() != null
                    && "InvalidInstanceID.NotFound".equals(e.awsErrorDetails().errorCode())) {
                return new Ec2InstanceStatus("terminated", null);
            }
            throw e;
        }
    }

    /** 인스턴스를 종료한다. 과금을 멈추는 유일한 경로 — 종료/실패정리 워커가 부른다. */
    public void terminate(CloudConnection connection, String instanceId) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            ec2.terminateInstances(TerminateInstancesRequest.builder()
                    .instanceIds(instanceId).build());
            log.info("EC2 인스턴스 종료 요청: instanceId={}", instanceId);
        } catch (Ec2Exception e) {
            if (e.awsErrorDetails() != null
                    && "InvalidInstanceID.NotFound".equals(e.awsErrorDetails().errorCode())) {
                log.debug("EC2 인스턴스가 이미 없음: instanceId={}", instanceId);
                return;
            }
            throw e;
        }
    }

    private Ec2Client client(AwsAccess access) {
        return Ec2Client.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
