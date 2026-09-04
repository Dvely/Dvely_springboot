package com.example.dvely.provisioning.application.service;

import com.example.dvely.deployment.application.port.out.FrontendServerHostingPort;
import com.example.dvely.provisioning.application.command.ServerProvisioningCommandService;
import com.example.dvely.provisioning.application.result.ServerProvisionSubmitResult;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.WebFrontendSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@link FrontendServerHostingPort} 구현 — 프론트 EC2 배포 요청을 provisioning 도메인의 웹 전용 서버
 * 프로비저닝으로 위임한다. 프론트 소스를 split(별도 프론트 저장소)로 넘겨 레포 루트를 프론트로 빌드하고
 * (S3 경로와 같은 가정), 백엔드 없는 nginx 서버로 DOCKER 프로비저닝한다. 과금 자원이라 승인을 거친다.
 */
@Service
@RequiredArgsConstructor
public class FrontendServerHostingAdapter implements FrontendServerHostingPort {

    private final ServerProvisioningCommandService serverCommandService;

    @Override
    public ServerSubmission provisionWebOnly(Request request) {
        // frontendRepo 를 split 로 넘긴다 — 레포 루트가 프론트다(hasWeb=true 라 webOnly submit 검증 통과).
        // instanceType=null → 기본 티어, bundledDb=null(백엔드 없음), DOCKER(nginx 컨테이너), webOnly=true.
        ServerProvisionSubmitResult result = serverCommandService.submit(
                request.ownerUserId(),
                request.projectId(),
                null,
                ServerDeployMode.DOCKER,
                null,
                new WebFrontendSpec(request.frontendRepo(), null, null),
                true);
        return new ServerSubmission(result.serverId(), result.approvalIds());
    }
}
