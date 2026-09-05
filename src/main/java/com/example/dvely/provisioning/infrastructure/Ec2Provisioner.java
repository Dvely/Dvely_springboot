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
import software.amazon.awssdk.services.ec2.model.DescribeAddressesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import software.amazon.awssdk.services.ec2.model.AllocateAddressRequest;
import software.amazon.awssdk.services.ec2.model.AssociateAddressRequest;
import software.amazon.awssdk.services.ec2.model.CreateSecurityGroupRequest;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DomainType;
import software.amazon.awssdk.services.ec2.model.ReleaseAddressRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSecurityGroupsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IpPermission;
import software.amazon.awssdk.services.ec2.model.IpRange;
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
import software.amazon.awssdk.services.ec2.model.Vpc;

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

    /**
     * 인스턴스 상태를 조회한다. running 이면 publicHost 가 나온다 — <b>publicIpAddress(=EIP IP) 우선</b>.
     * DNS 이름이 아니라 IP 로 두는 이유: 도메인 바인딩이 A 레코드로 이 IP 를 가리켜야 하고(IP 에는 CNAME
     * 불가), EIP 라 IP 가 안정적이다. url 표시(http://IP:port)에도 문제없다.
     */
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
            String host = (instance.publicIpAddress() == null || instance.publicIpAddress().isBlank())
                    ? instance.publicDnsName() : instance.publicIpAddress();
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

    private static final String SG_NAME = "qeploy-backend";

    /**
     * 백엔드 인바운드 보안그룹을 기본 VPC 에 보장하고 그 ID 를 돌려준다(멱등, 이미 있으면 재사용).
     * 여는 포트: 앱(8080, 직접 접속·헬스체크) + 443(Caddy HTTPS) + 80(Let's Encrypt ACME 챌린지).
     * SSH(22)는 열지 않는다 — 인스턴스에 접속하지 않는다(재설정이 필요 없게 Caddy on-demand 를 쓴다).
     */
    public String ensureSecurityGroup(CloudConnection connection, int appPort) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            var existing = ec2.describeSecurityGroups(DescribeSecurityGroupsRequest.builder()
                    .filters(Filter.builder().name("group-name").values(SG_NAME).build())
                    .build()).securityGroups();
            if (!existing.isEmpty()) {
                return existing.get(0).groupId();
            }
            String vpcId = defaultVpcId(ec2);
            String groupId = ec2.createSecurityGroup(CreateSecurityGroupRequest.builder()
                    .groupName(SG_NAME).description("Qeploy backend app port")
                    .vpcId(vpcId).build()).groupId();
            ec2.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
                    .groupId(groupId)
                    .ipPermissions(tcpFromAnywhere(appPort), tcpFromAnywhere(443), tcpFromAnywhere(80))
                    .build());
            log.info("EC2 보안그룹 생성: name={} groupId={} ports={},443,80", SG_NAME, groupId, appPort);
            return groupId;
        }
    }

    private static final String DB_SG_NAME = "qeploy-db";

    /**
     * RDS 인바운드 보안그룹을 기본 VPC 에 보장하고 그 ID 를 돌려준다(멱등, 이미 있으면 재사용).
     * MySQL(3306)·Postgres(5432)를 <b>기본 VPC CIDR 안에서만</b> 연다 — RDS 는
     * {@code publiclyAccessible=false} 라 사설 주소뿐이고, 같은 VPC 의 백엔드 EC2 만 닿으면 된다.
     *
     * <p>이 SG 를 명시하지 않고 RDS 를 만들면 <b>기본 VPC 보안그룹</b>이 붙는데, 그건 자기 SG 멤버의
     * 인바운드만 허용한다. 백엔드 EC2 는 {@code qeploy-backend} SG 라 멤버가 아니어서 3306 에 못 붙는다
     * (실계정 확인, 2026-09-03). 그래서 RDS 생성 시 이 SG 를 붙여야 한다.</p>
     */
    public String ensureDatabaseSecurityGroup(CloudConnection connection) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            var existing = ec2.describeSecurityGroups(DescribeSecurityGroupsRequest.builder()
                    .filters(Filter.builder().name("group-name").values(DB_SG_NAME).build())
                    .build()).securityGroups();
            if (!existing.isEmpty()) {
                return existing.get(0).groupId();
            }
            Vpc vpc = defaultVpc(ec2);
            String groupId = ec2.createSecurityGroup(CreateSecurityGroupRequest.builder()
                    .groupName(DB_SG_NAME).description("Qeploy managed database")
                    .vpcId(vpc.vpcId()).build()).groupId();
            ec2.authorizeSecurityGroupIngress(AuthorizeSecurityGroupIngressRequest.builder()
                    .groupId(groupId)
                    .ipPermissions(tcpFromCidr(3306, vpc.cidrBlock()), tcpFromCidr(5432, vpc.cidrBlock()))
                    .build());
            log.info("RDS 보안그룹 생성: name={} groupId={} cidr={} ports=3306,5432",
                    DB_SG_NAME, groupId, vpc.cidrBlock());
            return groupId;
        }
    }

    /** 임의 출처(0.0.0.0/0)에서 해당 TCP 포트를 여는 인그레스 규칙. */
    private IpPermission tcpFromAnywhere(int port) {
        return tcpFromCidr(port, "0.0.0.0/0");
    }

    /** 주어진 CIDR 에서 해당 TCP 포트를 여는 인그레스 규칙. */
    private IpPermission tcpFromCidr(int port, String cidr) {
        return IpPermission.builder()
                .ipProtocol("tcp").fromPort(port).toPort(port)
                .ipRanges(IpRange.builder().cidrIp(cidr).build())
                .build();
    }

    private String defaultVpcId(Ec2Client ec2) {
        return defaultVpc(ec2).vpcId();
    }

    private Vpc defaultVpc(Ec2Client ec2) {
        var vpcs = ec2.describeVpcs(DescribeVpcsRequest.builder()
                .filters(Filter.builder().name("isDefault").values("true").build())
                .build()).vpcs();
        if (vpcs.isEmpty()) {
            throw new IllegalStateException("기본 VPC 를 찾지 못했습니다. 계정에 기본 VPC 가 필요합니다.");
        }
        return vpcs.get(0);
    }

    /** 할당·연결한 Elastic IP. publicIp 는 안정 주소가 된다. */
    public record ElasticIp(String allocationId, String publicIp) {}

    /**
     * Elastic IP 를 할당해 인스턴스에 연결하고 allocationId·publicIp 를 돌려준다. 자동할당 public IP 는
     * stop·재배포마다 바뀌어 도메인이 깨지므로, 안정 주소가 필요한 백엔드에 EIP 를 붙인다.
     * <b>종료 시 반드시 release 해야 유휴 EIP 과금이 안 붙는다(호출자 책임).</b>
     */
    private static final int EIP_ASSOCIATE_RETRY = 12;
    private static final long EIP_ASSOCIATE_DELAY_MS = 4000;

    public ElasticIp allocateAndAssociateElasticIp(CloudConnection connection, String instanceId, String nameTag) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            var alloc = ec2.allocateAddress(AllocateAddressRequest.builder()
                    .domain(DomainType.VPC).build());
            String allocationId = alloc.allocationId();
            // allocate 이후 어느 단계(태그·연결)든 실패하면 방금 할당한 EIP 를 즉시 해제한다. 안 그러면
            // 호출자에게 allocationId 도 못 넘긴 채 미연결 EIP 가 유휴 과금으로 샌다(release 만 로그로
            // 남는 게 아니라 실제 돈이 붙고, 사용자가 콘솔에서 손으로 지워야 한다).
            try {
                // 종료 정리·고아 대조 때 태그로 되짚을 수 있게(allocationId 는 서버 행에 저장하지만 이중 안전).
                ec2.createTags(CreateTagsRequest.builder()
                        .resources(allocationId)
                        .tags(Tag.builder().key("Name").value(nameTag).build(),
                              Tag.builder().key("managed-by").value("qeploy").build())
                        .build());
                associateWithRetry(ec2, allocationId, instanceId);
            } catch (RuntimeException e) {
                try {
                    ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(allocationId).build());
                    log.warn("EIP 연결 실패 → 방금 할당한 EIP 해제: allocationId={}", allocationId);
                } catch (RuntimeException releaseErr) {
                    log.error("EIP 연결 실패 후 release 도 실패(고아 EIP 남음, 유휴 과금): allocationId={} 원인={}",
                            allocationId, releaseErr.toString());
                }
                throw e;
            }
            log.info("EIP 할당·연결: allocationId={} publicIp={} instanceId={}",
                    allocationId, alloc.publicIp(), instanceId);
            return new ElasticIp(allocationId, alloc.publicIp());
        }
    }

    /**
     * 이미 다른 인스턴스에 연결된 EIP 를 새 인스턴스로 <b>재연결</b>한다(재배포 블루그린 cutover). VPC EIP 는
     * {@code allowReassociation=true} 로 associate 한 번이면 옛 인스턴스에서 풀려 새 인스턴스로 옮겨간다
     * (별도 Disassociate 불필요). 새 인스턴스는 이미 RUNNING 이라 pending 재시도는 필요 없다. dnsTarget(IP)
     * 이 그대로라 도메인·인증서를 안 건드리고 트래픽만 새 인스턴스로 넘어간다.
     */
    public void reassociateElasticIp(CloudConnection connection, String allocationId, String newInstanceId) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            ec2.associateAddress(AssociateAddressRequest.builder()
                    .allocationId(allocationId)
                    .instanceId(newInstanceId)
                    .allowReassociation(true)
                    .build());
            log.info("EIP 재연결(재배포 cutover): allocationId={} → newInstanceId={}", allocationId, newInstanceId);
        }
    }

    /**
     * EIP 연결을 재시도한다. runInstances 직후 인스턴스는 잠깐 pending 이라 associate 가 "not in a valid
     * state"(IncorrectInstanceState)로 거부될 수 있다 — associable 해질 때까지 몇 차례 기다린다.
     */
    private void associateWithRetry(Ec2Client ec2, String allocationId, String instanceId) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= EIP_ASSOCIATE_RETRY; attempt++) {
            try {
                ec2.associateAddress(AssociateAddressRequest.builder()
                        .allocationId(allocationId).instanceId(instanceId).build());
                return;
            } catch (Ec2Exception e) {
                String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean transientState = "IncorrectInstanceState".equals(code)
                        || "InvalidInstanceID".equals(code)
                        || msg.contains("not in a valid state");
                if (attempt < EIP_ASSOCIATE_RETRY && transientState) {
                    last = e;
                    log.debug("EIP associate 대기(인스턴스 pending), 재시도 {}/{}: {}", attempt, EIP_ASSOCIATE_RETRY, code);
                    sleep(EIP_ASSOCIATE_DELAY_MS);
                    continue;
                }
                throw e;
            }
        }
        throw last;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** managed-by=qeploy 태그가 붙은 EIP 한 건. associated=false 면 미연결(고아 후보). */
    public record QeployEip(String allocationId, String publicIp, boolean associated) {}

    /**
     * 이 계정에서 우리가 만든(managed-by=qeploy) EIP 목록을 조회한다. 고아 EIP 자동 회수 워커가
     * 미연결(associated=false)이면서 어느 살아있는 서버도 소유하지 않은 것을 골라 release 하는 데 쓴다.
     */
    public java.util.List<QeployEip> listQeployElasticIps(CloudConnection connection) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            return ec2.describeAddresses(DescribeAddressesRequest.builder()
                    .filters(Filter.builder().name("tag:managed-by").values("qeploy").build())
                    .build()).addresses().stream()
                    .map(a -> new QeployEip(a.allocationId(), a.publicIp(),
                            a.associationId() != null && !a.associationId().isBlank()))
                    .toList();
        }
    }

        /**
     * EIP 를 해제한다(release). 유휴 EIP 과금을 멈추는 유일한 경로 — 종료 정리가 부른다. 인스턴스가
     * 종료되면 EIP 는 연결만 풀리고 할당은 남아(계속 과금) release 가 필요하다. 이미 없으면 조용히 지나간다.
     */
    public void releaseElasticIp(CloudConnection connection, String allocationId) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (Ec2Client ec2 = client(access)) {
            ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(allocationId).build());
            log.info("EIP 해제: allocationId={}", allocationId);
        } catch (Ec2Exception e) {
            if (e.awsErrorDetails() != null
                    && "InvalidAllocationID.NotFound".equals(e.awsErrorDetails().errorCode())) {
                log.debug("EIP 가 이미 없음: allocationId={}", allocationId);
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
