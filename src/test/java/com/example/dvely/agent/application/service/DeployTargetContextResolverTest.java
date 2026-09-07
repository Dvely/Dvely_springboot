package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.application.service.DeployTargetContextResolver.DeployTargetContext;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.model.ProjectCloudConnectionSetting;
import com.example.dvely.project.domain.repository.ProjectCloudConnectionSettingRepository;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.DeployStatus;
import com.example.dvely.project.domain.value.FrontendHostingType;
import com.example.dvely.project.domain.value.ProjectStatus;
import com.example.dvely.project.domain.value.RepositoryBindingStatus;
import com.example.dvely.project.domain.value.RepositoryHealthStatus;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeployTargetContextResolverTest {

    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final ProjectCloudConnectionSettingRepository cloudConnectionSettingRepository =
            mock(ProjectCloudConnectionSettingRepository.class);
    private final DeployTargetContextResolver resolver =
            new DeployTargetContextResolver(projectRepository, cloudConnectionSettingRepository);

    @Test
    void 클라우드_연결이_없으면_고를_수_있는_곳은_GitHub_Pages_뿐이다() {
        givenProject(DeployStatus.DRAFT);
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        DeployTargetContext context = resolver.resolve(11L).orElseThrow();

        assertThat(context.available()).containsExactly(FrontendHostingType.GITHUB_PAGES);
        assertThat(context.asPromptLine()).contains("no cloud connection selected");
    }

    @Test
    void 클라우드가_연결돼_있으면_S3_와_EC2_도_고를_수_있다() {
        givenProject(DeployStatus.DRAFT);
        when(cloudConnectionSettingRepository.findByProjectId(11L))
                .thenReturn(Optional.of(mock(ProjectCloudConnectionSetting.class)));

        DeployTargetContext context = resolver.resolve(11L).orElseThrow();

        assertThat(context.available()).containsExactly(
                FrontendHostingType.GITHUB_PAGES, FrontendHostingType.S3, FrontendHostingType.EC2);
        assertThat(context.asPromptLine()).contains("availableTargets=GITHUB_PAGES/S3/EC2");
    }

    /** 한 번이라도 배포했으면 되묻지 않는다 — 매 배포마다 "어디에 올릴까요" 를 묻는 것이 과잉이다. */
    @Test
    void DRAFT_를_벗어났으면_배포_이력이_있는_것으로_본다() {
        givenProject(DeployStatus.LIVE);
        when(cloudConnectionSettingRepository.findByProjectId(11L)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(11L).orElseThrow().everDeployed()).isTrue();
    }

    @Test
    void 프로젝트를_모르면_아무_사실도_만들어내지_않는다() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(99L)).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }

    private void givenProject(DeployStatus deployStatus) {
        LocalDateTime now = LocalDateTime.now();
        when(projectRepository.findById(11L)).thenReturn(Optional.of(new Project(
                11L, 1L, "my-project", ProjectStatus.ACTIVE, "vue", null, "fast",
                deployStatus, null, null, "octo/repo", "octo/repo",
                RepositoryVisibility.PUBLIC, RepositoryBindingStatus.BOUND,
                RepositoryHealthStatus.HEALTHY, false, now, now
        )));
    }
}
