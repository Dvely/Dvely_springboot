package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 프리뷰 짚기의 소스 후보(설계 §16.7). 찾는 말은 프리뷰 문서에서 온 값이라 셸 명령에 넣지 않고 환경변수로만 넘긴다.
 */
class PreviewPinSourceFinderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final DockerContainerService docker = mock(DockerContainerService.class);
    private final PreviewPinSourceFinder finder = new PreviewPinSourceFinder(docker);

    private static JsonNode place(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static final String SAVE = """
            {"element":{"type":"button","text":"저장","selector":"#profile-form > div.actions > button.btn.btn-primary"},
             "scopes":[{"kind":"element","type":"button","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}}],"defaultScope":0}
            """;

    @Test
    void 찾는_말은_요소의_id_클래스_글이고_명령에는_값이_들어가지_않는다() throws Exception {
        String hostile = """
                {"element":{"type":"p","id":"intro","text":"'; touch /tmp/pwned #  $(id)","selector":"#intro"},
                 "scopes":[{"kind":"element","type":"p","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}}],"defaultScope":0}
                """;
        when(docker.execWithExitCode(anyString(), anyString(), anyList()))
                .thenReturn(new DockerContainerService.ExecResult(0, ""));

        finder.find("c1", List.of(place(SAVE), place(hostile)));

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(docker).execWithExitCode(eq("c1"), command.capture(), env.capture());
        assertThat(command.getValue()).doesNotContain("touch").doesNotContain("$(id)").doesNotContain("저장")
                .doesNotContain("btn-primary").contains("\"$T0\"");
        assertThat(env.getValue()).containsExactly("T0=btn-primary", "T1=저장", "T2=intro", "T3='; touch /tmp/pwned # $(id)");
        // 짧은 공용 클래스(btn, 4자 미만)는 찾지 않는다 — 다른 버튼까지 잡는다
        assertThat(env.getValue()).noneMatch(e -> e.endsWith("=btn"));
    }

    @Test
    void 결과는_곳마다_까닭_순서와_파일_종류_순서로_3개까지이고_작업_공간_밖은_버린다() throws Exception {
        when(docker.execWithExitCode(anyString(), anyString(), anyList())).thenReturn(new DockerContainerService.ExecResult(0, """
                P0:TEXT ./index.html:54
                P0:CLASS ./styles.css:152
                P0:CLASS ./index.html:54
                P0:TEXT ./app.js:12
                P0:TEXT /etc/passwd:1
                P0:TEXT ../secret.txt:3
                P1:ID ./index.html:20
                garbage line
                """));

        List<List<JsonNode>> hints = finder.find("c1", List.of(place(SAVE), place("""
                {"element":{"type":"section","id":"plan"},"scopes":[]}
                """)));

        assertThat(hints.get(0)).extracting(h -> h.path("file").asText() + ":" + h.path("line").asInt() + ":" + h.path("reason").asText())
                .containsExactly("index.html:54:CLASS", "styles.css:152:CLASS", "app.js:12:TEXT");
        assertThat(hints.get(1)).extracting(h -> h.path("reason").asText()).containsExactly("ID");
    }

    @Test
    void 문장_칸을_고르면_그_문장을_찾는다() throws Exception {
        List<PreviewPinSourceFinder.Term> terms = finder.termsOf(List.of(place("""
                {"element":{"type":"p","text":"지금은 무료입니다. 팀원을 3명까지 초대할 수 있습니다.","selector":"#intro"},
                 "scope":0,"defaultScope":1,
                 "scopes":[{"kind":"text","type":"text","text":"팀원을 3명까지 초대할 수 있습니다.","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}},
                           {"kind":"element","type":"p","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}}]}
                """)));

        assertThat(terms).extracting(PreviewPinSourceFinder.Term::value).first().isEqualTo("팀원을 3명까지 초대할 수 있습니다.");
    }

    @Test
    void 컨테이너가_없거나_exec_가_실패하면_후보_없이_간다() throws Exception {
        assertThat(finder.find(null, List.of(place(SAVE)))).containsExactly(List.of());
        when(docker.execWithExitCode(anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("gone"));
        assertThat(finder.find("c1", List.of(place(SAVE)))).containsExactly(List.of());
    }
}
