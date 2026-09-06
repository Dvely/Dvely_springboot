package com.example.dvely.provisioning.infrastructure.external;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.provisioning.infrastructure.AcmCertificateProvisioner;
import com.example.dvely.provisioning.infrastructure.AcmCertificateProvisioner.CertInfo;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner;
import com.example.dvely.provisioning.infrastructure.CloudFrontDistributionProvisioner.DistributionInfo;
import com.example.dvely.provisioning.infrastructure.S3StaticSiteStore;
import com.example.dvely.provisioning.infrastructure.persistence.entity.CdnDeletionEntity;
import com.example.dvely.provisioning.infrastructure.persistence.repository.SpringDataCdnDeletionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link S3CdnProvisioningPort} 구현 — 프로젝트의 S3 배포 클라우드 연결을 해석해 ACM 인증서·CloudFront
 * 배포를 사용자 계정에 만든다. domainbinding(응용)은 포트만 알고, 이 인프라 어댑터가 provisioning 의
 * AWS 프로비저너들과 다리를 놓는다. 연결은 시스템 경로(승인된 바인딩·워커)라 소유자 검사 없이 findById 로 찾는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3CdnProvisioningAdapter implements S3CdnProvisioningPort {

    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;
    private final CloudConnectionRepository cloudConnectionRepository;
    private final AcmCertificateProvisioner acmProvisioner;
    private final CloudFrontDistributionProvisioner cloudFrontProvisioner;
    private final S3StaticSiteStore siteStore;
    private final SpringDataCdnDeletionRepository cdnDeletionRepository;

    @Override
    public String requestCertificate(Long projectId, String hostname) {
        return acmProvisioner.requestCertificate(resolveConnection(projectId), hostname);
    }

    @Override
    public AcmCertStatus describeCertificate(Long projectId, String certificateArn) {
        CertInfo info = acmProvisioner.describe(resolveConnection(projectId), certificateArn);
        return new AcmCertStatus(
                info.status(),
                info.validationRecordName(),
                info.validationRecordValue(),
                info.issued(),
                info.failed(),
                info.hasValidationRecord());
    }

    @Override
    public CdnDistribution createDistribution(Long projectId, String hostname, String certificateArn) {
        CloudConnection connection = resolveConnection(projectId);
        String originHost = siteStore.websiteOriginHost(connection, projectId);
        DistributionInfo info = cloudFrontProvisioner.createDistribution(
                connection, hostname, certificateArn, originHost);
        return new CdnDistribution(info.distributionId(), info.domainName());
    }

    @Override
    public void scheduleDistributionCleanup(Long projectId, String distributionId,
                                            String certificateArn, String hostname) {
        CloudConnection connection = resolveConnection(projectId);
        cdnDeletionRepository.save(CdnDeletionEntity.of(
                connection.getId(), distributionId, certificateArn, hostname));
        // 즉시 disable 을 시도해 서빙을 멈춘다(실패해도 리퍼가 다시 disable 후 삭제한다).
        try {
            cloudFrontProvisioner.disable(connection, distributionId);
        } catch (RuntimeException e) {
            log.warn("CloudFront 배포 즉시 비활성화 실패(리퍼가 재시도): distributionId={} 원인={}",
                    distributionId, e.toString());
        }
    }

    @Override
    public void deleteCertificate(Long projectId, String certificateArn) {
        try {
            acmProvisioner.deleteCertificate(resolveConnection(projectId), certificateArn);
        } catch (RuntimeException e) {
            log.warn("ACM 인증서 즉시 삭제 실패(무시): certArn={} 원인={}", certificateArn, e.toString());
        }
    }

    private CloudConnection resolveConnection(Long projectId) {
        return cloudConnectionSettingRepository.findByProjectId(projectId)
                .flatMap(setting -> cloudConnectionRepository.findById(setting.getCloudConnectionId()))
                .orElseThrow(() -> new NotFoundException(
                        "S3 프론트 HTTPS 는 연결된 클라우드가 있어야 합니다. 인프라 탭에서 클라우드 연결을 먼저 선택해주세요."));
    }
}
