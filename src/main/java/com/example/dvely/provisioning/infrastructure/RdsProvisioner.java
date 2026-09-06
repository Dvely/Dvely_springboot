package com.example.dvely.provisioning.infrastructure;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import com.example.dvely.provisioning.application.port.out.DatabaseProvisioner;
import com.example.dvely.provisioning.application.port.out.ProvisionResult;
import com.example.dvely.provisioning.application.port.out.ProvisionSpec;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import java.security.SecureRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DbInstanceNotFoundException;

/**
 * RDS DB 인스턴스를 사용자 AWS 계정(BYOC)에 프로비저닝한다. LOCAL 과 달리 <b>비동기</b>다 —
 * 생성이 수 분 걸리므로 동기 {@link #provision}으로는 안 되고, 승인 핸들러가 {@link #startCreation}
 * 으로 생성을 시작하고 상태 워커가 {@link #describe}로 폴링해 available 이 되면 접속정보를 채운다.
 *
 * <p>티어·스토리지는 고정(db.t3.micro / 20GB), 기본 VPC(publiclyAccessible=false), 엔진은 요청값.
 * 자격은 {@link AwsCredentialsResolver}로 매 호출마다 새로 얻는다(assume-role 세션이 짧다).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RdsProvisioner implements DatabaseProvisioner {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INSTANCE_CLASS = "db.t3.micro";
    private static final int ALLOCATED_STORAGE_GB = 20;
    private static final String DB_NAME = "app";
    private static final String MASTER_USERNAME = "qeadmin";   // RDS 는 admin 등 일부 이름을 예약한다

    private final AwsCredentialsResolver credentialsResolver;
    private final Ec2Provisioner ec2Provisioner;

    @Override
    public ProvisionMethod method() {
        return ProvisionMethod.RDS;
    }

    @Override
    public ProvisionResult provision(ProvisionSpec spec, String containerId) {
        // RDS 는 생성이 비동기라 이 동기 경로에 맞지 않는다. 승인 핸들러가 startCreation 을 쓴다.
        throw new UnsupportedOperationException("RDS 는 비동기 생성이라 startCreation 을 사용한다.");
    }

    /** RDS 인스턴스 식별자와 마스터 자격. host 는 생성 완료 후에야 생기므로 여기엔 없다. */
    public record RdsCreation(String instanceId, int port, String database, String username, String password) {
        @Override
        public String toString() {
            return "RdsCreation[instanceId=" + instanceId + ", port=" + port
                    + ", database=" + database + ", username=" + username + ", password=***]";
        }
    }

    /** 인스턴스 상태 스냅샷. host 는 available 이 되기 전에는 null. */
    public record RdsStatus(String status, String host) {}

    /**
     * 인스턴스 생성을 시작한다(즉시 반환). 생성이 끝날 때까지 기다리지 않는다 — 상태 워커가
     * describe 로 available 을 확인하고 host 를 채운다.
     */
    public RdsCreation startCreation(CloudConnection connection, DatabaseEngine engine, Long projectId) {
        String instanceId = "qeploy-" + projectId + "-" + shortRandom();
        String password = randomPassword();
        // 백엔드 EC2 가 사설로 붙을 수 있게 전용 SG(3306/5432 를 VPC 내부에서 허용)를 명시한다.
        // 안 붙이면 기본 SG(자기 멤버만 허용)가 잡혀 qeploy-backend SG 의 EC2 가 접속 못 한다.
        String dbSecurityGroupId = ec2Provisioner.ensureDatabaseSecurityGroup(connection);
        AwsAccess access = credentialsResolver.resolve(connection);
        try (RdsClient rds = client(access)) {
            rds.createDBInstance(CreateDbInstanceRequest.builder()
                    .dbInstanceIdentifier(instanceId)
                    .dbInstanceClass(INSTANCE_CLASS)
                    .engine(rdsEngine(engine))
                    .masterUsername(MASTER_USERNAME)
                    .masterUserPassword(password)
                    .allocatedStorage(ALLOCATED_STORAGE_GB)
                    .dbName(DB_NAME)
                    .vpcSecurityGroupIds(dbSecurityGroupId)
                    .publiclyAccessible(false)   // 백엔드가 같은 VPC 에서 사설로 붙는다
                    .build());
        }
        log.info("RDS 인스턴스 생성 시작: instanceId={} engine={} projectId={}", instanceId, engine, projectId);
        return new RdsCreation(instanceId, port(engine), DB_NAME, MASTER_USERNAME, password);
    }

    /** 인스턴스 상태를 조회한다. available 이면 endpoint 가 채워져 host 가 나온다. */
    public RdsStatus describe(CloudConnection connection, String instanceId) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (RdsClient rds = client(access)) {
            var instances = rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                    .dbInstanceIdentifier(instanceId).build()).dbInstances();
            if (instances.isEmpty()) {
                return new RdsStatus("deleted", null);
            }
            DBInstance instance = instances.get(0);
            String host = instance.endpoint() == null ? null : instance.endpoint().address();
            return new RdsStatus(instance.dbInstanceStatus(), host);
        } catch (DbInstanceNotFoundException e) {
            return new RdsStatus("deleted", null);
        }
    }

    /** 인스턴스를 삭제한다(최종 스냅샷 없이). RDS 는 자격이 필요해 deprovision(resourceId) 로는 못 지운다. */
    public void deleteInstance(CloudConnection connection, String instanceId) {
        AwsAccess access = credentialsResolver.resolve(connection);
        try (RdsClient rds = client(access)) {
            rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                    .dbInstanceIdentifier(instanceId)
                    .skipFinalSnapshot(true)
                    .deleteAutomatedBackups(true)
                    .build());
            log.info("RDS 인스턴스 삭제 요청: instanceId={}", instanceId);
        } catch (DbInstanceNotFoundException e) {
            log.debug("RDS 인스턴스가 이미 없음: instanceId={}", instanceId);
        }
    }

    @Override
    public void deprovision(String resourceId) {
        // 만료 회수 워커가 method 로 이 구현을 부르지만, RDS 는 expiresAt 이 없어 그 워커에 잡히지 않는다.
        // 실제 삭제는 자격이 필요하므로 deleteInstance(connection, id) 로 한다.
        throw new UnsupportedOperationException("RDS 삭제는 CloudConnection 이 필요하다. deleteInstance 를 사용한다.");
    }

    private RdsClient client(AwsAccess access) {
        return RdsClient.builder()
                .region(access.region())
                .credentialsProvider(access.credentialsProvider())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private String rdsEngine(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL -> "mysql";
            case POSTGRESQL -> "postgres";
        };
    }

    private int port(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL -> 3306;
            case POSTGRESQL -> 5432;
        };
    }

    private String shortRandom() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }

    private String randomPassword() {
        // RDS 마스터 비번은 / @ " 공백을 못 쓴다. 영숫자로만 둔다.
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }
}
