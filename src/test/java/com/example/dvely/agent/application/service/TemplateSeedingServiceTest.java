package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import com.example.dvely.template.application.port.out.TemplateCatalogPort;
import com.example.dvely.template.domain.model.Template;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateSeedingServiceTest {

    private static final String CONTAINER = "container-1";
    private static final Long PROJECT_ID = 7L;
    private static final String SOURCE_URL =
            "https://dvely.github.io/qeploy-templates/src/landing-minimal.tar.gz";

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TemplateCatalogPort templateCatalogPort;

    @Mock
    private DockerContainerService dockerService;

    private TemplateSeedingService service() {
        return new TemplateSeedingService(projectRepository, templateCatalogPort, dockerService);
    }

    private Project project(String startMode, String templateType) {
        return new Project(1L, "my-site", startMode, templateType, "fast", RepositoryVisibility.PRIVATE);
    }

    private Template template(String sourceUrl) {
        return new Template("landing-minimal", "미니멀 랜딩", "설명", List.of("landing"), "vanilla",
                "index.html", List.of(), "https://demo", sourceUrl);
    }

    @Test
    @DisplayName("템플릿으로 시작한 빈 프로젝트에 씨앗을 푼다")
    void seedsIntoEmptyWorkspace() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("template", "landing-minimal")));
        when(templateCatalogPort.findById("landing-minimal")).thenReturn(Optional.of(template(SOURCE_URL)));
        // 첫 호출은 비어 있고, 푼 뒤에는 파일이 있다.
        when(dockerService.exec(eq(CONTAINER), contains("ls -A"))).thenReturn("", "index.html");
        when(dockerService.execWithExitCode(eq(CONTAINER), contains("tar -xz")))
                .thenReturn(new DockerContainerService.ExecResult(0, ""));
        when(dockerService.execWithExitCode(eq(CONTAINER), contains("commit")))
                .thenReturn(new DockerContainerService.ExecResult(0, ""));

        Optional<Template> seeded = service().seedIfNeeded(CONTAINER, PROJECT_ID);

        assertThat(seeded).isPresent();
        // 기준 커밋이 없으면 변경 내역이 템플릿 전체를 "새 파일" 로 잡아, 요청한 3줄 수정이
        // 1만 자에 묻힌다(dev project 53 에서 실제로 그랬다).
        verify(dockerService).execWithExitCode(eq(CONTAINER), contains("commit -q -m 'template: landing-minimal'"));
        verify(dockerService).execWithExitCode(eq(CONTAINER), contains(SOURCE_URL));
        // --no-same-owner 가 빠지면 dev 에서 났던 실패가 그대로 재현된다: 씨앗은 CI 러너(uid 1001)가
        // 묶어서 tar 가 대상 디렉터리를 1001 로 chown 하고, CAP_DAC_OVERRIDE 없는 root 가 그 안에
        // 쓰려다 첫 파일에서 Permission denied 로 죽는다.
        verify(dockerService).execWithExitCode(eq(CONTAINER), contains("--no-same-owner"));
    }

    @Test
    @DisplayName("blank 로 시작한 프로젝트는 건드리지 않는다")
    void skipsBlankProject() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("blank", null)));

        assertThat(service().seedIfNeeded(CONTAINER, PROJECT_ID)).isEmpty();

        verifyNoInteractions(dockerService, templateCatalogPort);
    }

    @Test
    @DisplayName("이미 파일이 있으면 덮지 않는다 — 두 번째 요청과 clone 해온 저장소가 여기 해당한다")
    void skipsWhenWorkspaceNotEmpty() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("template", "landing-minimal")));
        when(dockerService.exec(eq(CONTAINER), contains("ls -A"))).thenReturn("index.html");

        assertThat(service().seedIfNeeded(CONTAINER, PROJECT_ID)).isEmpty();

        verify(dockerService, never()).execWithExitCode(anyString(), anyString());
    }

    @Test
    @DisplayName("내려받기에 실패하면 조용히 넘어가지 않는다")
    void failsLoudlyWhenDownloadFails() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("template", "landing-minimal")));
        when(templateCatalogPort.findById("landing-minimal")).thenReturn(Optional.of(template(SOURCE_URL)));
        when(dockerService.exec(eq(CONTAINER), contains("ls -A"))).thenReturn("");
        when(dockerService.execWithExitCode(eq(CONTAINER), contains("tar -xz")))
                .thenReturn(new DockerContainerService.ExecResult(1, "wget: bad address"));

        assertThatThrownBy(() -> service().seedIfNeeded(CONTAINER, PROJECT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("landing-minimal")
                // 출력이 빠지면 무엇이 막혔는지 알 수 없어 컨테이너에 들어가 재현해야 한다.
                .hasMessageContaining("wget: bad address");
    }

    @Test
    @DisplayName("종료코드가 0 이어도 파일이 없으면 실패로 본다")
    void failsWhenNothingWasExtracted() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("template", "landing-minimal")));
        when(templateCatalogPort.findById("landing-minimal")).thenReturn(Optional.of(template(SOURCE_URL)));
        // 풀기 전에도 후에도 비어 있다. tar 는 빈 입력에 0 을 낼 수 있다.
        when(dockerService.exec(eq(CONTAINER), contains("ls -A"))).thenReturn("", "");
        when(dockerService.execWithExitCode(eq(CONTAINER), contains("tar -xz")))
                .thenReturn(new DockerContainerService.ExecResult(0, ""));

        assertThatThrownBy(() -> service().seedIfNeeded(CONTAINER, PROJECT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("파일이 없습니다");
    }

    @Test
    @DisplayName("셸에 넘길 수 없는 소스 주소는 실행하지 않는다")
    void rejectsUnsafeSourceUrl() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("template", "landing-minimal")));
        when(templateCatalogPort.findById("landing-minimal"))
                .thenReturn(Optional.of(template("https://evil.test/x.tar.gz';id;'")));
        when(dockerService.exec(eq(CONTAINER), contains("ls -A"))).thenReturn("");

        assertThatThrownBy(() -> service().seedIfNeeded(CONTAINER, PROJECT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("신뢰할 수 없습니다");

        verify(dockerService, never()).execWithExitCode(anyString(), anyString());
    }

    @Test
    @DisplayName("카탈로그에서 사라진 템플릿은 침묵하지 않는다")
    void failsWhenTemplateNoLongerInCatalog() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("template", "landing-minimal")));
        when(dockerService.exec(eq(CONTAINER), contains("ls -A"))).thenReturn("");
        when(templateCatalogPort.findById("landing-minimal")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().seedIfNeeded(CONTAINER, PROJECT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("landing-minimal");
    }


    @Test
    @DisplayName("기준 커밋에 실패해도 씨딩은 성공이다 — diff 가 덜 편해질 뿐 작업물은 멀쩡하다")
    void baselineFailureDoesNotFailSeeding() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project("template", "landing-minimal")));
        when(templateCatalogPort.findById("landing-minimal")).thenReturn(Optional.of(template(SOURCE_URL)));
        when(dockerService.exec(eq(CONTAINER), contains("ls -A"))).thenReturn("", "index.html");
        when(dockerService.execWithExitCode(eq(CONTAINER), contains("tar -xz")))
                .thenReturn(new DockerContainerService.ExecResult(0, ""));
        when(dockerService.execWithExitCode(eq(CONTAINER), contains("commit")))
                .thenReturn(new DockerContainerService.ExecResult(1, "git: not found"));

        assertThat(service().seedIfNeeded(CONTAINER, PROJECT_ID)).isPresent();
    }
}
