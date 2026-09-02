package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.environment.domain.repository.EnvironmentVariableRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.repository.ProvisionedServerRepository;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import com.example.dvely.provisioning.infrastructure.Ec2InstanceRoleProvisioner;
import com.example.dvely.provisioning.infrastructure.Ec2Provisioner;
import com.example.dvely.provisioning.infrastructure.S3ArtifactStore;
import com.example.dvely.provisioning.infrastructure.SsmParameterStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BackendDeployRunnerTest {

    @Mock private ProvisionedServerRepository serverRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private BackendJarBuildService buildService;
    @Mock private S3ArtifactStore s3;
    @Mock private SsmParameterStore ssm;
    @Mock private Ec2InstanceRoleProvisioner roleProvisioner;
    @Mock private Ec2Provisioner ec2;
    @Mock private ProvisionedDatabaseRepository databaseRepository;
    @Mock private EnvironmentVariableRepository environmentVariableRepository;

    @InjectMocks private BackendDeployRunner runner;

    private static final Long OWNER = 7L;
    private static final Long PROJECT = 10L;
    private static final Long CONN_ID = 11L;

    private ProvisionedServer building() {
        return new ProvisionedServer(1L, PROJECT, "t3.micro", ServerStatus.BUILDING,
                CONN_ID, null, null, 8080, 99L, null, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private void stubHappyPath(Path jar) {
        when(cloudConnectionRepository.findById(CONN_ID)).thenReturn(Optional.of(connection()));
        when(buildService.buildJar(OWNER, PROJECT)).thenReturn(jar);
        when(s3.bucketNameFor(any())).thenReturn("qeploy-artifacts-x");
        when(s3.jarKeyFor(PROJECT)).thenReturn("10/app.jar");
        when(databaseRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT)).thenReturn(List.of());
        when(environmentVariableRepository.findByProjectIdOrderByScopeAscKeyAsc(PROJECT)).thenReturn(List.of());
        when(roleProvisioner.ensureInstanceProfile(any(), eq(PROJECT), anyString())).thenReturn("qeploy-instance-10");
        when(ec2.ensureSecurityGroup(any(), eq(8080))).thenReturn("sg-1");
        when(ssm.latestAmazonLinux2023Ami(any())).thenReturn("ami-1");
    }

    @Test
    void deploySucceedsAndMovesToProvisioning() throws IOException {
        Path jar = Files.createTempFile("test-app", ".jar");
        stubHappyPath(jar);
        when(ec2.launch(any(), any())).thenReturn("i-123");

        runner.deploy(building());

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.PROVISIONING);
        assertThat(saved.getValue().getInstanceId()).isEqualTo("i-123");
        assertThat(Files.exists(jar)).isFalse();   // 임시 jar 는 정리된다
    }

    @Test
    void buildFailureMarksServerFailedAndNeverLaunches() {
        when(cloudConnectionRepository.findById(CONN_ID)).thenReturn(Optional.of(connection()));
        when(buildService.buildJar(OWNER, PROJECT)).thenThrow(new BackendBuildException("gradle 빌드 실패"));

        runner.deploy(building());

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.FAILED);
        verify(ec2, never()).launch(any(), any());
    }

    @Test
    void missingCloudConnectionFailsWithoutBuilding() {
        when(cloudConnectionRepository.findById(CONN_ID)).thenReturn(Optional.empty());

        runner.deploy(building());

        ArgumentCaptor<ProvisionedServer> saved = ArgumentCaptor.forClass(ProvisionedServer.class);
        verify(serverRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ServerStatus.FAILED);
        verify(buildService, never()).buildJar(anyLong(), anyLong());
    }

    @Test
    void rollsBackInstanceWhenPostLaunchStepFails() throws IOException {
        Path jar = Files.createTempFile("test-app", ".jar");
        stubHappyPath(jar);
        when(ec2.launch(any(), any())).thenReturn("i-999");
        // launch 이후 저장이 터지면(=인스턴스는 이미 생김) 과금 자원을 롤백해야 한다.
        when(serverRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> runner.deploy(building())).isInstanceOf(RuntimeException.class);

        verify(ec2).terminate(any(), eq("i-999"));   // 방금 만든 인스턴스를 정리
    }

    private CloudConnection connection() {
        return new CloudConnection(CONN_ID, OWNER, CloudProvider.AWS, "production", "123456789012",
                "ap-northeast-2", null, "ACCESS_KEY", "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD", null, null, null, null, null,
                CloudConnectionStatus.CONNECTED, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
