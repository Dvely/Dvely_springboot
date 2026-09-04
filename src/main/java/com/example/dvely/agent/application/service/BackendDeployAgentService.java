package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.dto.AgentStep;
import com.example.dvely.agent.application.service.CodeAgentService.CodeResult;
import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.provisioning.application.command.DatabaseProvisioningCommandService;
import com.example.dvely.provisioning.application.command.ServerProvisioningCommandService;
import com.example.dvely.provisioning.application.result.ProvisionSubmitResult;
import com.example.dvely.provisioning.application.result.ServerProvisionSubmitResult;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ServerDeployMode;
import com.example.dvely.provisioning.domain.value.WebFrontendSpec;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * BACKEND_DEPLOY 스텝: 사용자가 대화로 "운영에 백엔드 올려줘 / 실제 서버로 배포 / 프로덕션 배포"라고
 * 하면, 이 스텝이 사용자 AWS 계정에 RDS(필요 시)+EC2 를 배포하도록 <b>요청</b>한다. 프리뷰
 * (RUNTIME_SETUP)·GitHub Pages 정적(DEPLOY)과 다르다.
 *
 * <p>실제 자원 생성은 과금이라 승인을 거친다 — 이 스텝은 기존 C1/C2 커맨드 서비스를 불러 대기 행과
 * 표준 승인(DATABASE_PROVISION·SERVER_PROVISION)을 만들고, 사용자가 평소 승인 카드로 승인하면
 * 워커가 실제로 만든다. 즉 이 스텝은 "배포를 시작시키는" 얇은 오케스트레이터다(직접 만들지 않는다).</p>
 *
 * <p>DB 를 함께 만들면(dbEngine 지정) EC2 배포 워커는 그 DB 가 READY 될 때까지 기다렸다가(의존성
 * 게이트) 배포한다 — 앱이 DB 접속정보를 env 로 받아야 하기 때문이다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackendDeployAgentService {

    private final ServerProvisioningCommandService serverCommandService;
    private final DatabaseProvisioningCommandService databaseCommandService;
    private final ProvisionedDatabaseRepository databaseRepository;

    public CodeResult execute(AgentStep step, Long userId, Long projectId) {
        if (projectId == null) {
            log.warn("[BACKEND_DEPLOY] 프로젝트가 없어 배포를 건너뜁니다 | userId={}", userId);
            return new CodeResult(null,
                    "프로젝트가 아직 없어 백엔드 배포를 건너뛰었습니다. 프로젝트를 먼저 만든 뒤 다시 요청해주세요.");
        }

        String instanceType = blankToNull(step.parameters().get("instanceType"));
        DatabaseEngine dbEngine = parseEngine(step.parameters().get("dbEngine"));
        ServerDeployMode deployMode = parseDeployMode(step.parameters().get("deployMode"));
        DatabaseEngine bundledDb = parseEngine(step.parameters().get("bundledDbEngine"));
        WebFrontendSpec web = new WebFrontendSpec(
                blankToNull(step.parameters().get("frontendRepo")),
                blankToNull(step.parameters().get("frontendDir")),
                blankToNull(step.parameters().get("apiPathPrefix")));
        // 번들 DB·웹 컨테이너(같은 EC2 에 compose)는 DOCKER 배포에서만 가능하므로 편의상 DOCKER 로 승격.
        if (bundledDb != null || web.hasWeb()) {
            deployMode = ServerDeployMode.DOCKER;
        }

        try {
            List<Long> approvalIds = new ArrayList<>();
            boolean dbRequested = false;

            // DB 를 원하는데(dbEngine 지정) 아직 RDS DB 가 없으면 함께 요청한다. 이미 있거나 생성 중이면
            // 중복으로 만들지 않는다. 단 번들 DB 를 쓰면(그게 곧 DB 다) RDS 는 만들지 않는다 — 둘은 대안.
            if (dbEngine != null && bundledDb == null && !hasActiveRdsDatabase(projectId)) {
                ProvisionSubmitResult db = databaseCommandService.provision(
                        userId, projectId, ProvisionMethod.RDS, dbEngine);
                approvalIds.addAll(db.approvalIds());
                dbRequested = true;
            }

            ServerProvisionSubmitResult server =
                    serverCommandService.submit(userId, projectId, instanceType, deployMode, bundledDb, web);
            approvalIds.addAll(server.approvalIds());

            log.info("[BACKEND_DEPLOY] 배포 요청 접수 | projectId={} dbRequested={} bundledDb={} web={} approvalIds={}",
                    projectId, dbRequested, bundledDb, web.hasWeb(), approvalIds);
            return new CodeResult(null, buildSummary(dbRequested, bundledDb != null));
        } catch (NotFoundException | IllegalStateException e) {
            // "클라우드 연결이 필요합니다" 같은 사용자 조치 안내 — 태스크를 실패로 떨구지 않고 그대로 보여준다.
            log.info("[BACKEND_DEPLOY] 배포 요청 불가(사용자 조치 필요) | projectId={} 사유={}", projectId, e.getMessage());
            return new CodeResult(null, e.getMessage());
        }
    }

    private boolean hasActiveRdsDatabase(Long projectId) {
        return databaseRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(db -> db.getMethod() == ProvisionMethod.RDS)
                .anyMatch(BackendDeployAgentService::isActive);
    }

    private static boolean isActive(ProvisionedDatabase db) {
        ProvisionStatus s = db.getStatus();
        return s == ProvisionStatus.READY || s == ProvisionStatus.PENDING || s == ProvisionStatus.PROVISIONING;
    }

    private String buildSummary(boolean dbRequested, boolean bundledDb) {
        if (dbRequested) {
            return "운영 백엔드 배포를 요청했습니다. 과금 자원이라 승인이 필요합니다 — 데이터베이스와 "
                    + "서버 생성 각각을 승인해주세요. DB 를 먼저 승인하면 서버가 그 DB 가 준비된 뒤 자동으로 "
                    + "연결되어 뜹니다. 승인 후 몇 분 뒤 접속 주소가 나옵니다.";
        }
        if (bundledDb) {
            return "운영 백엔드 서버 배포를 요청했습니다(DB 포함). 과금 자원이라 승인이 필요합니다 — 승인하면 "
                    + "같은 서버에 앱과 데이터베이스를 함께 띄웁니다(별도 RDS 없이). 승인 후 몇 분 뒤 접속 주소가 나옵니다.";
        }
        return "운영 백엔드 서버 배포를 요청했습니다. 과금 자원이라 승인이 필요합니다 — 승인하면 소스를 "
                + "빌드해 서버를 띄웁니다. 승인 후 몇 분 뒤 접속 주소가 나옵니다.";
    }

    private DatabaseEngine parseEngine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DatabaseEngine.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            return null;   // 알 수 없는 엔진은 DB 없이 서버만 배포
        }
    }

    /** 배포 형태(step 파라미터). 생략/알 수 없으면 NATIVE(jar). LLM 이 "docker"를 주면 이미지 배포. */
    private ServerDeployMode parseDeployMode(String value) {
        if (value == null || value.isBlank()) {
            return ServerDeployMode.NATIVE;
        }
        try {
            return ServerDeployMode.valueOf(value.trim().toUpperCase());
        } catch (RuntimeException e) {
            return ServerDeployMode.NATIVE;
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
