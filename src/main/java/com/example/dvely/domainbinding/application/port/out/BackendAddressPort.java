package com.example.dvely.domainbinding.application.port.out;

import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.util.Optional;

/**
 * 프로젝트의 실행 중 EC2 서버 공개 주소를 도메인바인딩에 넘기는 포트. AWS 호스팅 어댑터가 도메인이
 * 가리킬 대상(EIP)을 A 레코드로 걸 때 쓴다. 구현은 인프라 계층에서 provisioning 을 읽어 다리를 놓는다 —
 * 도메인바인딩 응용 계층은 이 포트에만 의존한다(도메인 경계 유지).
 *
 * <p>한 프로젝트에 백엔드 서버와 독립 프론트(webOnly) 서버가 함께 뜰 수 있어, 도메인이 어느 쪽을
 * 가리킬지 <b>webOnly 로 갈라</b> 각각의 EIP 를 준다. 안 가르면 프론트만 떠 있는 프로젝트에서 프론트
 * EIP 가 "백엔드 IP"로 새어 나가 백엔드 도메인이 프론트를 가리키는 오분류가 생긴다.</p>
 */
public interface BackendAddressPort {

    /** 이 프로젝트의 RUNNING 백엔드(webOnly=false) 서버 공개 IP. 없으면 empty(서버 미배포·미기동). */
    Optional<String> resolveRunningBackendIp(Long projectId);

    /** 이 프로젝트의 RUNNING 독립 프론트(webOnly=true) 서버 공개 IP. 없으면 empty. */
    Optional<String> resolveRunningFrontendHost(Long projectId);

    /**
     * 이 도메인({@code hostingTarget})이 가리키는, 프로젝트의 RUNNING 서버 id. FE 가 도메인에서 그 서버로
     * 이어가는(상세·로그) 데 쓴다. 위 host 해석과 <b>같은 RUNNING+webOnly 규칙</b>을 재사용하므로, 이 값이
     * 있다는 것은 dnsTarget 이 가리키는 그 서버가 실제 실행 중이라는 뜻이다({@code AWS_EC2_FRONTEND}→프론트,
     * {@code AWS}→백엔드). EC2 대상이 아니면(GitHub Pages·S3·GCP = 가리킬 서버 없음) empty.
     */
    Optional<Long> resolveServerId(Long projectId, DomainHostingTarget hostingTarget);
}
