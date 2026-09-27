package com.example.dvely.preview.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.presentation.dto.TaskStatusResponse;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code previewUrl} 이라는 이름은 <b>열 수 있는 주소 하나</b>에만 붙는다 (#392).
 *
 * <h2>왜 이런 테스트가 필요한가</h2>
 * 이 이슈의 수정은 필드를 <b>지우는</b> 것이었다. 지운 것은 되돌려도 아무 테스트가 깨지지 않는다 —
 * 누군가 "태스크 응답에서 프리뷰 주소를 주면 편하겠다"며 다시 넣으면, 그 순간 함정이 그대로
 * 돌아오고 아무도 모른다. 그래서 <b>부재 자체를 단정</b>한다.
 *
 * <h2>무엇이 함정이었나</h2>
 * 같은 이름이 세 응답에 있었고 계약이 셋이었다.
 * <ul>
 *   <li>{@code POST /preview-sessions/&#123;id&#125;/access} — 최신이고 <b>열린다</b>(쿠키를 같이 발급)</li>
 *   <li>{@code GET /projects/&#123;id&#125;/preview-session} — 최신이지만 <b>쿠키 없이는 401</b></li>
 *   <li>{@code GET /agent/tasks/&#123;taskId&#125;} — <b>낡은 스냅샷</b>이라 회전 뒤 404</li>
 * </ul>
 * 승인 메시지가 세 번째를 믿고 주소를 박았고, 사용자가 프리뷰를 여는 순간(=회전) 죽은 링크가 됐다
 * (#391). 대화 이력은 남으므로 영구히 죽은 링크였다.
 */
class PreviewUrlContractTest {

    @Test
    @DisplayName("태스크 응답에는 previewUrl 이 없다 — 회전으로 죽는 스냅샷이었다")
    void taskResponseCarriesNoPreviewUrl() {
        assertThat(componentNames(TaskStatusResponse.class))
                .doesNotContain("previewUrl")
                // 대체 신호는 남아 있어야 한다. 둘 다 없으면 FE 는 폴링을 멈출 신호를 잃는다.
                .contains("previewCreated");
    }

    @Test
    @DisplayName("프로젝트 프리뷰 세션 응답에는 previewUrl 이 없다 — 쿠키 없이는 열리지 않았다")
    void projectPreviewSessionResponseCarriesNoPreviewUrl() {
        assertThat(componentNames(ProjectPreviewSessionResponse.class))
                .doesNotContain("previewUrl")
                // 볼 수 있는지는 이쪽이 말한다.
                .contains("status", "sessionId");
    }

    @Test
    @DisplayName("access 응답은 previewUrl 을 계속 가진다 — 유일하게 열 수 있는 주소다")
    void accessResponseKeepsPreviewUrl() {
        // 이 단정이 위 둘과 짝이다. "previewUrl 을 다 없애자" 가 아니라 "한 곳에만 둔다" 가
        // 이 이슈의 결론이므로, 그 한 곳이 사라지는 것도 회귀다.
        assertThat(componentNames(PreviewAccessResponse.class)).contains("previewUrl");
    }

    private static java.util.List<String> componentNames(Class<?> record) {
        RecordComponent[] components = record.getRecordComponents();
        assertThat(components)
                .as("%s 가 record 가 아니면 이 계약 테스트는 아무것도 보지 않는다", record.getSimpleName())
                .isNotNull();
        return Arrays.stream(components).map(RecordComponent::getName).toList();
    }
}
