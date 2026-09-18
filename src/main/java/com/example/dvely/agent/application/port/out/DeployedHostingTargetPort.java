package com.example.dvely.agent.application.port.out;

import com.example.dvely.domainbinding.domain.value.DomainHostingTarget;
import java.util.Optional;

/**
 * 이 프로젝트가 <b>실제로 배포된 곳</b>에 맞는 도메인 {@link DomainHostingTarget} 을 돌려준다.
 *
 * <p>도메인 바인딩이 대상을 명시받지 못했을 때 무조건 {@code GITHUB_PAGES} 로 떨어지면, EC2 에
 * 백엔드를 배포해 놓고 "이 앱에 도메인 붙여줘" 라고 해도 GitHub Pages 를 설정하려다 404 가 난다
 * (배포 e2e 실측). 그 기본값을 실제 배포 상태(RUNNING EC2 서버)에서 유추하기 위한 포트다.</p>
 *
 * <p>구현은 인프라 계층에서 provisioning 을 읽어 다리를 놓는다(agent 응용은 이 포트에만 의존). EC2
 * 서버가 없으면(정적 프론트·GitHub Pages 프로젝트) empty 를 돌려주고, 호출부가 기존 기본값으로 폴백한다.</p>
 */
public interface DeployedHostingTargetPort {

    Optional<DomainHostingTarget> resolveDeployedHostingTarget(Long projectId);
}
