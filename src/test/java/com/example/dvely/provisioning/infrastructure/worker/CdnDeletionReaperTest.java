package com.example.dvely.provisioning.infrastructure.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.infrastructure.AcmCertificateProvisioner;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner.DistributionState;
import com.example.dvely.provisioning.infrastructure.persistence.entity.CdnDeletionEntity;
import com.example.dvely.provisioning.infrastructure.persistence.repository.SpringDataCdnDeletionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
class CdnDeletionReaperTest {

    @Mock private SpringDataCdnDeletionRepository deletionRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private CloudFrontDistributionProvisioner cloudFrontProvisioner;
    @Mock private AcmCertificateProvisioner acmProvisioner;
    @InjectMocks private CdnDeletionReaper reaper;

    private CdnDeletionEntity row() {
        return CdnDeletionEntity.of(5L, "E123", "arn:cert", "s3app.qeploy.com");
    }

    private void givenQueue(CdnDeletionEntity row) {
        when(deletionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
    }

    @Test
    void claimLost_skipsReap() {
        givenQueue(row());
        when(deletionRepository.claimForReap(any(), any(), any(), any())).thenReturn(0);   // 다른 인스턴스가 쥠

        reaper.reap();

        // 정리 로직에 진입조차 안 함 — 중복 CloudFront 호출 방지.
        verifyNoInteractions(cloudConnectionRepository, cloudFrontProvisioner, acmProvisioner);
        verify(deletionRepository, never()).delete(any());
    }

    @Test
    void claimWon_disabledAndDeployed_deletesDistributionCertAndRow() {
        CdnDeletionEntity row = row();
        givenQueue(row);
        when(deletionRepository.claimForReap(any(), any(), any(), any())).thenReturn(1);   // 리스 획득
        CloudConnection conn = mock(CloudConnection.class);
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(conn));
        when(cloudFrontProvisioner.getState(conn, "E123"))
                .thenReturn(new DistributionState(false, true));   // 비활성 + Deployed → 삭제 가능

        reaper.reap();

        verify(cloudFrontProvisioner).delete(conn, "E123");
        verify(acmProvisioner).deleteCertificate(conn, "arn:cert");
        verify(deletionRepository).delete(row);   // 큐에서 제거
    }

    @Test
    void claimWon_stillEnabled_disablesAndWaits_noDelete() {
        CdnDeletionEntity row = row();
        givenQueue(row);
        when(deletionRepository.claimForReap(any(), any(), any(), any())).thenReturn(1);
        CloudConnection conn = mock(CloudConnection.class);
        when(cloudConnectionRepository.findById(5L)).thenReturn(Optional.of(conn));
        when(cloudFrontProvisioner.getState(conn, "E123"))
                .thenReturn(new DistributionState(true, true));   // 아직 enabled → disable 후 대기

        reaper.reap();

        verify(cloudFrontProvisioner).disable(conn, "E123");
        verify(cloudFrontProvisioner, never()).delete(any(), eq("E123"));   // 이번 주기엔 삭제 안 함
        verify(deletionRepository, never()).delete(any());
    }
}
