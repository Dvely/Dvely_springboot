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

        // --no-same-owner 가 없으면 안 된다. 씨앗 tarball 은 CI 러너(uid 1001)가 묶어서 소유자가
        // 그대로 기록돼 있고, root 로 푸는 tar 는 그 기록대로 대상 디렉터리를 1001 로 chown 한다.
        // 컨테이너는 CapDrop=ALL 에 CHOWN 만 되살린 상태라 chown 은 성공하지만, 그 직후 root 가
        // 남의 디렉터리에 쓰려다 막힌다(CAP_DAC_OVERRIDE 가 없다). 첫 파일에서 Permission denied 로
        // 죽는다 — dev 에서 실제로 이렇게 실패했다.
        DockerContainerService.ExecResult result = dockerService.execWithExitCode(
                containerId,
                "mkdir -p " + ContainerPaths.APP_DIR
                        + " && wget -qO- '" + sourceUrl + "' | tar -xz --no-same-owner -C "
                        + ContainerPaths.APP_DIR);

        if (!result.succeeded()) {
            // 출력을 함께 담는다. exit 코드만 남기면 무엇이 막혔는지 알 수 없어, 원인을 찾으려고
            // 컨테이너에 들어가 같은 명령을 다시 돌려야 한다(실제로 그렇게 됐다).
            throw new IllegalStateException(
                    "템플릿을 내려받지 못했습니다: " + templateId + " (exit=" + result.exitCode() + ")"
                            + firstLineOf(result.output()));
        }
        // wget 이 파이프 앞이라 종료코드가 tar 의 것이다. 빈 입력에도 tar 가 0 을 낼 수 있어
        // 결과를 직접 확인한다 — "성공했는데 아무것도 없는" 상태가 가장 나쁘다.
        if (!hasFiles(containerId)) {
            throw new IllegalStateException("템플릿을 풀었지만 파일이 없습니다: " + templateId);
        }

        recordBaseline(containerId, templateId);

        log.info("[TemplateSeed] 씨딩 완료 | projectId={} templateId={} url={}", projectId, templateId, sourceUrl);
        return Optional.of(template);
    }

    /**
     * 갓 푼 템플릿을 기준 커밋으로 남긴다.
     *
     * <p>이게 없으면 변경 내역이 템플릿 전체를 "새 파일" 로 잡는다. 사용자가 보고 싶은 것은 자기가
     * 요청한 변경분인데, 실측에서 3줄 수정이 1만 자 diff 에 묻혔다(2026-09-10 dev, project 53).
     * 기준선을 깔아두면 {@code ChangeService} 가 그 대비 차이만 뜬다.</p>
     *
     * <p><b>실패해도 씨딩을 실패시키지 않는다.</b> 기준선이 없으면 diff 가 예전처럼 전체를 보여줄
     * 뿐이고, 그건 불편하지 그른 것이 아니다. 여기서 태스크를 죽이면 부가 기능 때문에 본 작업을
     * 잃는다 — 씨딩 자체의 실패(사용자가 고른 것과 다른 결과물)와는 성격이 다르다.</p>
     *
     * <p>커밋에는 신원이 필요하다. 컨테이너에 git 전역 설정이 없으므로 {@code -c} 로 이 커밋에만
     * 준다 — {@code git config} 로 남기면 뒤에 오는 push 의 커밋 작성자까지 바꾼다.</p>
     */
    private void recordBaseline(String containerId, String templateId) {
        String git = ContainerPaths.diffGit();
        String identity = "-c user.email=noreply@qeploy.dev -c user.name=Qeploy ";

        DockerContainerService.ExecResult result = dockerService.execWithExitCode(
                containerId,
                ContainerPaths.inApp("(apk add --no-cache git >/dev/null 2>&1 || true) && "
                        + "rm -rf " + ContainerPaths.DIFF_GIT_DIR + " && "
                        + git + "init -q && "
                        + git + "add -A && "
                        + git + identity + "commit -q -m 'template: " + templateId + "'"));

        if (!result.succeeded()) {
            log.warn("[TemplateSeed] 기준 커밋을 남기지 못했습니다 — 변경 내역이 템플릿 전체를 새 파일로 "
                    + "보여줍니다. templateId={} exit={}{}", templateId, result.exitCode(),
                    firstLineOf(result.output()));
        }
    }

    /** 예외 메시지에 들어가므로 한 줄로 자른다. 전체 출력은 로그가 아니라 여기서만 쓴다. */
    private String firstLineOf(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String first = output.strip().lines().findFirst().orElse("");
        return first.isBlank() ? "" : " — " + (first.length() > 200 ? first.substring(0, 200) : first);
    }

    private boolean hasFiles(String containerId) {
        String output = dockerService.exec(
                containerId, "ls -A " + ContainerPaths.APP_DIR + " 2>/dev/null | head -1");
        return output != null && !output.isBlank();
    }
}
