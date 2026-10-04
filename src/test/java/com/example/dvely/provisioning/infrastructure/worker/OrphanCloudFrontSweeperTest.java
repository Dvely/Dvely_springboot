package com.example.dvely.provisioning.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.application.service.AwsCredentialFailureReporter;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.provisioning.application.port.out.ActiveCdnDistributionPort;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner.OwnedDistribution;
import com.example.dvely.provisioning.infrastructure.persistence.entity.CdnDeletionEntity;
import com.example.dvely.provisioning.infrastructure.persistence.repository.SpringDataCdnDeletionRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrphanCloudFrontSweeperTest {

    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private CloudFrontDistributionProvisioner cloudFrontProvisioner;
    @Mock private ActiveCdnDistributionPort activeCdnDistributionPort;
    @Mock private SpringDataCdnDeletionRepository deletionRepository;
    @Mock private AwsCredentialFailureReporter credentialFailureReporter;
    @InjectMocks private OrphanCloudFrontSweeper sweeper;

    private CloudConnection awsConnection() {
        CloudConnection c = mock(CloudConnection.class);
        lenient().when(c.getId()).thenReturn(5L);
        when(cloudConnectionRepository.findAllByProvider(CloudProvider.AWS)).thenReturn(List.of(c));
        return c;
    }

    private OwnedDistribution dist(String id) {
        return new OwnedDistribution(id, "arn:cert", "s3app.qeploy.com");
    }

    @Test
    void orphan_notTrackedNotQueued_isEnqueuedForDeletion() {
        CloudConnection c = awsConnection();
        when(cloudFrontProvisioner.listOwnedDistributions(c)).thenReturn(List.of(dist("E-orphan")));
        when(activeCdnDistributionPort.trackedDistributionIds()).thenReturn(Set.of());   // 어디에도 추적 안 됨
        when(deletionRepository.existsByDistributionId("E-orphan")).thenReturn(false);

        sweeper.sweep();

        ArgumentCaptor<CdnDeletionEntity> saved = ArgumentCaptor.forClass(CdnDeletionEntity.class);
        verify(deletionRepository).save(saved.capture());
        assertThat(saved.getValue().getDistributionId()).isEqualTo("E-orphan");
        assertThat(saved.getValue().getCloudConnectionId()).isEqualTo(5L);
    }

    @Test
    void trackedByActiveBinding_neverEnqueued() {
        // 안전 핵심: 활성 바인딩이 참조하는 라이브 배포는 절대 지우지 않는다.
        CloudConnection c = awsConnection();
        when(cloudFrontProvisioner.listOwnedDistributions(c)).thenReturn(List.of(dist("E-live")));
        when(activeCdnDistributionPort.trackedDistributionIds()).thenReturn(Set.of("E-live"));

        sweeper.sweep();

        verify(deletionRepository, never()).save(any());
    }

    @Test
    void alreadyInDeletionQueue_skipped() {
        CloudConnection c = awsConnection();
        when(cloudFrontProvisioner.listOwnedDistributions(c)).thenReturn(List.of(dist("E-queued")));
        when(activeCdnDistributionPort.trackedDistributionIds()).thenReturn(Set.of());
        when(deletionRepository.existsByDistributionId("E-queued")).thenReturn(true);

        sweeper.sweep();

        verify(deletionRepository, never()).save(any());
    }

    @Test
    void createdBetweenSnapshotAndEnqueue_reCheckPreventsDeletion() {
        // 스냅샷엔 없지만 큐잉 직전 재확인엔 있는 배포 → 방금 생성된 라이브 → 큐잉하지 않는다(경합 안전장치).
        CloudConnection c = awsConnection();
        when(cloudFrontProvisioner.listOwnedDistributions(c)).thenReturn(List.of(dist("E-fresh")));
        when(activeCdnDistributionPort.trackedDistributionIds())
                .thenReturn(Set.of())            // ① 스냅샷: 없음
                .thenReturn(Set.of("E-fresh"));  // ② 큐잉 직전 재확인: 있음(방금 생성됨)
        when(deletionRepository.existsByDistributionId("E-fresh")).thenReturn(false);

        sweeper.sweep();

        verify(deletionRepository, never()).save(any());
    }

    @Test
    void 한_계정이_실패해도_다른_계정은_계속_훑는다() {
        // 이 catch 경로에는 테스트가 없었다 — #429 로 분기가 늘었으니 여기서 고정한다.
        CloudConnection bad = mock(CloudConnection.class);
        CloudConnection good = mock(CloudConnection.class);
        lenient().when(bad.getId()).thenReturn(1L);
        lenient().when(good.getId()).thenReturn(2L);
        when(cloudConnectionRepository.findAllByProvider(CloudProvider.AWS))
                .thenReturn(List.of(bad, good));
        RuntimeException failure = new RuntimeException("ListDistributions 실패");
        when(cloudFrontProvisioner.listOwnedDistributions(bad)).thenThrow(failure);
        when(cloudFrontProvisioner.listOwnedDistributions(good)).thenReturn(List.of(dist("E-orphan")));
        when(activeCdnDistributionPort.trackedDistributionIds()).thenReturn(Set.of());
        when(deletionRepository.existsByDistributionId("E-orphan")).thenReturn(false);

        sweeper.sweep();

        verify(deletionRepository).save(any(CdnDeletionEntity.class));
        // #429: 보고는 실패한 그 연결의 id 로 간다. 잘못 넘기면 멀쩡한 연결이 "자격 만료" 로 찍힌다.
        verify(credentialFailureReporter).reportIfCredentialFailure(1L, failure);
        verify(credentialFailureReporter, never()).reportIfCredentialFailure(eq(2L), any());
    }
}
