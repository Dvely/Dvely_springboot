package com.example.dvely.agent.application.service;

import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.FrontendHostingType;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 결정 에이전트가 계획을 세우기 전에 알아야 할 프로젝트 사실만 모은다.
 *
 * <p>결정 에이전트는 사용자 발화만 본다. 그래서 "무엇을 만들지" 는 판단해도 "이 프로젝트가 지금
 * 어떤 상태인지" 는 모른 채 결정한다. 그 공백이 두 가지 조용한 오작동을 낳았다.</p>
 *
 * <p><b>배포 위치</b> — DEPLOY 스텝에 배포 위치 축 자체가 없어 언제나 프로젝트 기본값
 * (GITHUB_PAGES)으로 나갔다. 물어보려면 먼저 "고를 수 있는 곳이 어디인지" 를 알아야 한다.</p>
 *
 * <p><b>스택 선택</b> — 프로젝트에 코드가 없는데도 프롬프트가 "기존 프로젝트의 수정으로 다루고
 * 스캐폴딩하지 말라" 고 단언했다. 실제로는 CODE 에이전트가 스캐폴딩했고(2026-09-07 dev, project 44:
 * {@code npm create vite@latest app -- --template react}), 사용자가 React 를 말한 적이 없는데도
 * React 19 프로젝트가 만들어졌다. 코드 유무를 알아야 "첫 빌드니까 스택을 묻는다" 가 성립한다.</p>
 *
 * <p>여기서는 사실만 만든다. 무엇을 묻고 무엇을 묻지 않을지는 시스템 프롬프트의 규칙이 정한다.</p>
 */
@Service
@RequiredArgsConstructor
public class ProjectDecisionContextResolver {

    private final ProjectRepository projectRepository;
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository;

    /**
     * {@code projectId} 가 없거나 프로젝트를 찾지 못하면 {@link Optional#empty()} — 호출부는 프롬프트에
     * 아무 줄도 붙이지 않는다. 사실을 모르면서 아는 척하는 줄을 넣는 것보다, 모델이 사용자에게
     * 물어보게 두는 편이 안전하다.
     */
    public Optional<ProjectDecisionContext> resolve(Long projectId) {
        if (projectId == null) {
            return Optional.empty();
        }
        return projectRepository.findById(projectId).map(this::toContext);
    }

    private ProjectDecisionContext toContext(Project project) {
        return new ProjectDecisionContext(
                project.getRepositoryBindingStatus() == RepositoryBindingStatus.BOUND
                        || project.getDeployStatus() != DeployStatus.DRAFT,
                project.getFrontendHostingType(),
                project.getDeployStatus() != DeployStatus.DRAFT,
                cloudConnectionSettingRepository.findByProjectId(project.getId()).isPresent()
        );
    }

    /**
     * @param hasCode        저장소가 연결됐거나 한 번이라도 배포된 프로젝트. 그렇다면 스택은 이미
     *                       정해져 있으므로 다시 묻지 않는다
     * @param frontendHosting 프로젝트에 저장된 프론트 호스팅. 재배포는 사용자가 바꾸라고 하지 않는 한 이걸 따른다
     * @param everDeployed   한 번이라도 배포를 요청한 적이 있는지({@link DeployStatus#DRAFT} 를 벗어났는지)
     * @param cloudConnected 프로젝트에 클라우드 연결이 선택돼 있는지 — S3·EC2 의 전제 조건
     */
    public record ProjectDecisionContext(
            boolean hasCode,
            FrontendHostingType frontendHosting,
            boolean everDeployed,
            boolean cloudConnected
    ) {

        /** 지금 이 프로젝트가 실제로 배포될 수 있는 곳. 클라우드가 없으면 GitHub Pages 하나뿐이다. */
        public List<FrontendHostingType> availableHostingTargets() {
            return cloudConnected
                    ? List.of(FrontendHostingType.GITHUB_PAGES, FrontendHostingType.S3, FrontendHostingType.EC2)
                    : List.of(FrontendHostingType.GITHUB_PAGES);
        }

        /**
         * 프로젝트를 어떻게 다룰지. 코드가 없는 프로젝트에 "수정으로 다루고 스캐폴딩하지 말라" 고
         * 말하면 안 된다 — 첫 CODE 스텝이 실제로 스캐폴딩하기 때문이다.
         */
        public String asProjectLine(Long projectId) {
            return hasCode
                    ? "[Project context: projectId=" + projectId
                            + ". This project already has code. Treat the latest user request as a "
                            + "modification of it — do not scaffold a new project.]"
                    : "[Project context: projectId=" + projectId
                            + ". This project has no code yet, so the first CODE step will scaffold it "
                            + "from scratch. Build inside this project — do not create another one.]";
        }

        /** 사실만 적은 한 줄. 판단은 시스템 프롬프트의 규칙에 맡긴다. */
        public String asFactsLine() {
            return "[Project facts: hasCode=" + hasCode
                    + ", frontendHosting=" + frontendHosting
                    + ", everDeployed=" + everDeployed
                    + ", availableHostingTargets=" + availableHostingTargets().stream()
                            .map(Enum::name)
                            .collect(Collectors.joining("/"))
                    + (cloudConnected ? "" : " (no cloud connection selected for this project)")
                    + ".]";
        }
    }
}
