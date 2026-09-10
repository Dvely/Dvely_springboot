package com.example.dvely.agent.application.service;

import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.template.domain.model.Template;
import com.example.dvely.template.application.port.out.TemplateCatalogPort;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사용자가 고른 템플릿을 컨테이너 작업 디렉터리에 푼다.
 *
 * 이것이 붙기 전까지 templateType 은 저장만 되고 읽히지 않았다 — 템플릿을 골라도 백지에서
 * 생성됐다. 여기서 씨앗을 먼저 깔아야 CODE 프롬프트가 "만들어라" 에서 "고쳐라" 로 바뀐다.
 *
 * <p><b>실패하면 조용히 넘어가지 않는다.</b> 씨딩에 실패했는데 그냥 진행하면 사용자가 고른
 * 템플릿과 전혀 다른 결과물이 "성공" 으로 나온다 — 고치려던 그 버그를 형태만 바꿔 재현하는
 * 셈이다. 그래서 예외를 던져 태스크를 실패로 닫는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateSeedingService {

    private static final String TEMPLATE_START_MODE = "template";

    /**
     * 이 URL 은 셸 명령에 들어간다. 카탈로그는 우리 저장소가 발행하지만, 거기 실린 값을 그대로
     * 셸에 넘기는 구조 자체를 두지 않는다 — 카탈로그가 오염되면 컨테이너 안 임의 명령 실행이 된다.
     */
    private static final Pattern SAFE_SOURCE_URL = Pattern.compile("^https://[A-Za-z0-9._~:/-]+\\.tar\\.gz$");

    private final ProjectRepository projectRepository;
    private final TemplateCatalogPort templateCatalogPort;
    private final DockerContainerService dockerService;

    /**
     * @return 실제로 씨딩했으면 그 템플릿. 템플릿으로 시작한 프로젝트가 아니거나 이미 코드가
     *         있으면 비어 있다(두 번째 요청부터는 씨딩하지 않는다)
     */
    public Optional<Template> seedIfNeeded(String containerId, Long projectId) {
        if (projectId == null) {
            return Optional.empty();
        }

        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || !TEMPLATE_START_MODE.equals(project.getStartMode())) {
            return Optional.empty();
        }

        String templateId = project.getTemplateType();
        if (templateId == null || templateId.isBlank()) {
            return Optional.empty();
        }

        // 이미 파일이 있으면 손대지 않는다. 첫 요청에서 씨딩한 뒤 두 번째 요청이 들어온 경우거나,
        // GitHub 저장소에서 clone 해온 경우다 — 어느 쪽이든 덮으면 사용자 작업물이 사라진다.
        if (hasFiles(containerId)) {
            log.debug("[TemplateSeed] 이미 파일이 있어 건너뜀 | projectId={} templateId={}", projectId, templateId);
            return Optional.empty();
        }

        Template template = templateCatalogPort.findById(templateId)
                .orElseThrow(() -> new IllegalStateException(
                        "프로젝트에 저장된 템플릿을 카탈로그에서 찾을 수 없습니다: " + templateId));

        String sourceUrl = template.sourceUrl();
        if (sourceUrl == null || !SAFE_SOURCE_URL.matcher(sourceUrl).matches()) {
            throw new IllegalStateException("템플릿 소스 주소를 신뢰할 수 없습니다: " + templateId);
        }

        DockerContainerService.ExecResult result = dockerService.execWithExitCode(
                containerId,
                "mkdir -p " + ContainerPaths.APP_DIR
                        + " && wget -qO- '" + sourceUrl + "' | tar -xz -C " + ContainerPaths.APP_DIR);

        if (!result.succeeded()) {
            throw new IllegalStateException(
                    "템플릿을 내려받지 못했습니다: " + templateId + " (exit=" + result.exitCode() + ")");
        }
        // wget 이 파이프 앞이라 종료코드가 tar 의 것이다. 빈 입력에도 tar 가 0 을 낼 수 있어
        // 결과를 직접 확인한다 — "성공했는데 아무것도 없는" 상태가 가장 나쁘다.
        if (!hasFiles(containerId)) {
            throw new IllegalStateException("템플릿을 풀었지만 파일이 없습니다: " + templateId);
        }

        log.info("[TemplateSeed] 씨딩 완료 | projectId={} templateId={} url={}", projectId, templateId, sourceUrl);
        return Optional.of(template);
    }

    private boolean hasFiles(String containerId) {
        String output = dockerService.exec(
                containerId, "ls -A " + ContainerPaths.APP_DIR + " 2>/dev/null | head -1");
        return output != null && !output.isBlank();
    }
}
