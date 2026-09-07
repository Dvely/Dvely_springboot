package com.example.dvely.agent.application.service;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.FrontendHostingType;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 결정 에이전트가 "이 프론트를 어디에 배포할지" 를 판단하는 데 필요한 프로젝트 사실만 모은다.
 *
 * <p>이게 없으면 결정 에이전트는 배포 위치를 물어볼 수도, 고를 수도 없다. 실제로 DEPLOY 스텝에는
 * 배포 위치 파라미터 자체가 없었고 {@code DeployAgentService} 는 언제나 프로젝트 기본값(GITHUB_PAGES)
 * 으로 배포했다 — S3·EC2 프론트 호스팅이 REST 로는 이미 되는데 채팅에서는 도달할 수 없었다.</p>
 *
 * <p>세 가지만 본다. <b>현재 설정</b>(재배포는 이걸 유지해야 한다), <b>배포 이력 유무</b>(한 번도
 * 배포한 적 없을 때만 물어야 매 배포마다 되묻지 않는다), <b>클라우드 연결 여부</b>(S3 와 EC2 는
 * 사용자 AWS 계정을 쓴다 — 연결이 없으면 고를 수 있는 곳은 GitHub Pages 뿐이고, 그런데도 선택지로
 * 내밀면 사용자가 고른 뒤 {@code DeploymentCommandService.validateFrontendHostingSupported} 에서
 * 거부당한다).</p>
 */
@Service
@RequiredArgsConstructor
public class DeployTargetContextResolver {

    private final ProjectRepository projectRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;

    /**
     * {@code projectId} 가 없거나 프로젝트를 찾지 못하면 {@link Optional#empty()} — 호출부는 프롬프트에
     * 아무 줄도 붙이지 않는다. 사실을 모르면서 아는 척하는 줄을 넣는 것보다, 모델이 사용자에게
     * 물어보게 두는 편이 안전하다.
     */
    public Optional<DeployTargetContext> resolve(Long projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        return projectRepository.findById(projectId).map(this::toContext);
    }

    private DeployTargetContext toContext(Project project) {
        return new DeployTargetContext(
                project.getFrontendHostingType(),
                project.getDeployStatus() != DeployStatus.DRAFT,
                cloudConnectionSettingRepository.findByProjectId(project.getId()).isPresent()
        );
    }

    /**
     * @param current        프로젝트에 저장된 프론트 호스팅. 재배포는 사용자가 바꾸라고 하지 않는 한 이걸 따른다
     * @param everDeployed   한 번이라도 배포를 요청한 적이 있는지({@link DeployStatus#DRAFT} 를 벗어났는지)
     * @param cloudConnected 프로젝트에 클라우드 연결이 선택돼 있는지 — S3·EC2 의 전제 조건
     */
    public record DeployTargetContext(
            FrontendHostingType current,
            boolean everDeployed,
            boolean cloudConnected
    ) {

        /** 지금 이 프로젝트가 실제로 배포될 수 있는 곳. 클라우드가 없으면 GitHub Pages 하나뿐이다. */
        public List<FrontendHostingType> available() {
            return cloudConnected
                    ? List.of(FrontendHostingType.GITHUB_PAGES, FrontendHostingType.S3, FrontendHostingType.EC2)
                    : List.of(FrontendHostingType.GITHUB_PAGES);
        }

        /** 결정 프롬프트에 붙일 한 줄. 사실만 적고 판단은 시스템 프롬프트의 규칙에 맡긴다. */
        public String asPromptLine() {
            return "[Frontend hosting context: current=" + current
                    + ", everDeployed=" + everDeployed
                    + ", availableTargets=" + available().stream()
                            .map(Enum::name)
                            .collect(Collectors.joining("/"))
                    + (cloudConnected ? "" : " (no cloud connection selected for this project)")
                    + ".]";
        }
    }
}
