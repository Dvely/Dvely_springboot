package com.example.dvely.domainbinding.infrastructure.worker;

import com.example.dvely.domainbinding.application.port.out.CloudflareDnsPort;
import com.example.dvely.domainbinding.application.port.out.HttpsProbePort;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort.AcmCertStatus;
import com.example.dvely.domainbinding.application.port.out.S3CdnProvisioningPort.CdnDistribution;
import com.example.dvely.domainbinding.domain.model.DomainBinding;
import com.example.dvely.domainbinding.domain.repository.DomainBindingRepository;
import com.example.dvely.domainbinding.domain.value.CertificateStatus;
import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import com.example.dvely.domainbinding.domain.value.DomainStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * S3 프론트 HTTPS(AWS_S3_FRONTEND) 도메인의 CloudFront+ACM 프로비저닝을 진행하는 워커. 바인딩은 인증서만
 * 요청해 PROVISIONING 으로 두고, 이 워커가 단계별로 밀어 올린다(RDS 프로비저닝 워커와 같은 폴링·멱등 방식):
 *
 * <ol>
 *   <li>인증서 DNS 검증 CNAME 을 우리 Cloudflare 존에 넣는다(proxied=false).</li>
 *   <li>인증서가 ISSUED 되면 CloudFront 배포를 만들고, 도메인→CloudFront 최종 CNAME 을 건다(proxied=false).</li>
 *   <li>실제 https 가 서빙되면 CONNECTED 로 올린다(CloudFront Deployed 완료 신호).</li>
 * </ol>
 *
 * <p>상태는 저장된 자원 id 로 판단한다(certArn·validationRecordId·distributionId). 원자적 claim 없음 —
 * describe 는 읽기, 각 mutation 은 즉시 저장해 다음 주기에 같은 단계를 되풀이하지 않는다. 관리형 서브도메인
 * 만 대상이라 모든 DNS 레코드가 우리 존에 들어간다(커스텀 도메인 S3 HTTPS 는 사용자 DNS 필요, 후속).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3CdnProvisionWorker {

    private static final int BATCH = 20;
    // 인증서 검증(~수 분) + CloudFront 배포 Deployed(~15분)까지 넉넉히. 넘으면 FAILED 로 종결한다.
    private static final Duration TTL = Duration.ofMinutes(45);

    private final DomainBindingRepository domainBindingRepository;
    private final S3CdnProvisioningPort cdnProvisioningPort;
    private final CloudflareDnsPort cloudflareDnsPort;
    private final HttpsProbePort httpsProbePort;

    @Scheduled(fixedDelayString = "${qeploy.provisioning.s3-cdn-poll-interval-ms:30000}")
    public void pollProvisioning() {
        for (DomainBinding domain : domainBindingRepository.findByStatus(DomainStatus.PROVISIONING, BATCH)) {
            if (domain.getHostingTarget() != DomainHostingTarget.AWS_S3_FRONTEND) {
                continue;   // PROVISIONING 은 이 워커가 소유하는 S3 프론트 도메인만 처리한다
            }
            try {
                advance(domain);
            } catch (RuntimeException e) {
                // 이 도메인만 건너뛰고 다음 주기에 다시 본다 — 한 도메인의 일시 오류가 배치를 멈추지 않게.
                log.warn("S3 CDN 프로비저닝 진행 실패(다음 주기 재시도): domainId={} hostname={} 원인={}",
                        domain.getId(), domain.getHostname(), e.toString());
            }
        }
    }

    private void advance(DomainBinding domain) {
        if (isExpired(domain)) {
            domain.fail();
            domainBindingRepository.save(domain);
            log.warn("S3 CDN 프로비저닝 TTL 초과로 FAILED: domainId={} hostname={}",
                    domain.getId(), domain.getHostname());
            return;
        }
        if (domain.getCloudfrontDistributionId() == null) {
            advanceCertAndDistribution(domain);
        } else {
            advanceHttpsReadiness(domain);
        }
    }

    /** 인증서 검증 → ISSUED → CloudFront 배포 생성 + 최종 CNAME 단계. */
    private void advanceCertAndDistribution(DomainBinding domain) {
        AcmCertStatus cert = cdnProvisioningPort.describeCertificate(
                domain.getProjectId(), domain.getAcmCertificateArn());
        if (cert.failed()) {
            domain.fail();
            domainBindingRepository.save(domain);
            log.warn("ACM 인증서 검증 실패로 FAILED: domainId={} hostname={} status={}",
                    domain.getId(), domain.getHostname(), cert.status());
            return;
        }
        if (cert.issued()) {
            // 배포 생성 → CloudFront 도메인으로 최종 CNAME(proxied=false). 배포 id 를 즉시 저장해 재생성 방지.
            CdnDistribution distribution = cdnProvisioningPort.createDistribution(
                    domain.getProjectId(), domain.getHostname(), domain.getAcmCertificateArn());
            String finalRecordId = cloudflareDnsPort.createCnameRecord(
                    domain.getHostname(), distribution.cloudfrontDomain(), false);
            domain.assignCloudfrontDistribution(
                    distribution.distributionId(), distribution.cloudfrontDomain(), finalRecordId);
            domainBindingRepository.save(domain);
            log.info("CloudFront 배포 생성 + 최종 CNAME: domainId={} hostname={} distributionId={} → {}",
                    domain.getId(), domain.getHostname(),
                    distribution.distributionId(), distribution.cloudfrontDomain());
            return;
        }
        // 아직 검증 중 — 검증 CNAME 이 없으면 우리 존에 넣는다(한 번만).
        if (cert.hasValidationRecord() && domain.getAcmValidationRecordId() == null) {
            String recordId = cloudflareDnsPort.createCnameRecord(
                    stripTrailingDot(cert.validationRecordName()),
                    stripTrailingDot(cert.validationRecordValue()),
                    false);
            domain.assignAcmValidationRecord(recordId);
            domainBindingRepository.save(domain);
            log.info("ACM 검증 CNAME 등록: domainId={} hostname={} record={}",
                    domain.getId(), domain.getHostname(), stripTrailingDot(cert.validationRecordName()));
        }
        // 그 외(검증 레코드 아직 없음·검증 진행 중): 다음 주기에 다시 본다.
    }

    /** CloudFront 배포가 https 로 실제 서빙되면 CONNECTED 로 올린다(Deployed 완료 신호). */
    private void advanceHttpsReadiness(DomainBinding domain) {
        if (httpsProbePort.isHttpsServing(domain.getHostname())) {
            domain.markVerificationChecked(true, true, CertificateStatus.ACTIVE, null);
            domainBindingRepository.save(domain);
            log.info("S3 프론트 HTTPS 연결 완료(CONNECTED): domainId={} hostname={}",
                    domain.getId(), domain.getHostname());
        }
        // 아직 https 미서빙(배포 전파 중) — 다음 주기에 다시 본다.
    }

    private boolean isExpired(DomainBinding domain) {
        LocalDateTime createdAt = domain.getCreatedAt();
        return createdAt != null && createdAt.plus(TTL).isBefore(LocalDateTime.now());
    }

    /** ACM 검증 레코드 name/value 는 FQDN 이라 끝에 점이 붙는다 — Cloudflare 등록 전 제거. */
    private String stripTrailingDot(String value) {
        if (value == null) {
            return null;
        }
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
