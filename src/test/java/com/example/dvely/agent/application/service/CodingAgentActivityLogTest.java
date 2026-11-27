package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.application.port.out.CodingAgentActivity.Kind;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 코딩 에이전트가 한 일을 타임라인 줄로(#600). 화면은 이벤트의 글을 그대로 그린다.
 */
class CodingAgentActivityLogTest {

    private final TaskStore taskStore = mock(TaskStore.class);
    private final CodingAgentActivityLog log = new CodingAgentActivityLog(taskStore);

    @Test
    void 한_일마다_종류와_글을_남긴다() {
        Consumer<CodingAgentActivity> sink = log.forTask("task-1");

        sink.accept(new CodingAgentActivity(Kind.READ, "styles.css"));
        sink.accept(new CodingAgentActivity(Kind.EDIT, "styles.css"));
        sink.accept(new CodingAgentActivity(Kind.COMMAND, "npm run build"));
        sink.accept(new CodingAgentActivity(Kind.SEARCH, "btn-primary"));
        sink.accept(new CodingAgentActivity(Kind.MESSAGE, "저장 버튼만 키웠습니다."));
        sink.accept(new CodingAgentActivity(Kind.DELETE, "src/old.css"));

        verify(taskStore).appendActivityEvent("task-1", "CODING_READ", "파일 읽기 · styles.css");
        verify(taskStore).appendActivityEvent("task-1", "CODING_EDIT", "파일 고치기 · styles.css");
        verify(taskStore).appendActivityEvent("task-1", "CODING_COMMAND", "명령 실행 · npm run build");
        verify(taskStore).appendActivityEvent("task-1", "CODING_SEARCH", "찾기 · btn-primary");
        verify(taskStore).appendActivityEvent("task-1", "CODING_MESSAGE", "에이전트: 저장 버튼만 키웠습니다.");
        verify(taskStore).appendActivityEvent("task-1", "CODING_DELETE", "파일 지우기 · src/old.css");
    }

    @Test
    void 바로_앞과_같은_줄은_다시_남기지_않는다() {
        Consumer<CodingAgentActivity> sink = log.forTask("task-1");

        sink.accept(new CodingAgentActivity(Kind.READ, "a.css"));
        sink.accept(new CodingAgentActivity(Kind.READ, "a.css"));
        sink.accept(new CodingAgentActivity(Kind.EDIT, "a.css"));
        sink.accept(new CodingAgentActivity(Kind.READ, "a.css"));

        verify(taskStore, times(2)).appendActivityEvent("task-1", "CODING_READ", "파일 읽기 · a.css");
        verify(taskStore, times(1)).appendActivityEvent("task-1", "CODING_EDIT", "파일 고치기 · a.css");
    }

    @Test
    void 한_실행에_상한까지만_남기고_넘으면_한_번_알린다() {
        Consumer<CodingAgentActivity> sink = log.forTask("task-1");

        for (int i = 0; i < CodingAgentActivityLog.MAX_EVENTS + 20; i++) {
            sink.accept(new CodingAgentActivity(Kind.READ, "f" + i + ".js"));
        }

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(taskStore, times(CodingAgentActivityLog.MAX_EVENTS)).appendActivityEvent(eq("task-1"), type.capture(), anyString());
        assertThat(type.getAllValues()).last().isEqualTo("CODING_MORE");
        assertThat(type.getAllValues()).filteredOn("CODING_MORE"::equals).hasSize(1);
    }

    @Test
    void 실행마다_새로_센다_그리고_저장이_실패해도_던지지_않는다() {
        Consumer<CodingAgentActivity> first = log.forTask("task-1");
        first.accept(new CodingAgentActivity(Kind.READ, "a.css"));
        Consumer<CodingAgentActivity> second = log.forTask("task-1");
        second.accept(new CodingAgentActivity(Kind.READ, "a.css"));
        verify(taskStore, times(2)).appendActivityEvent("task-1", "CODING_READ", "파일 읽기 · a.css");

        doThrow(new IllegalStateException("db")).when(taskStore).appendActivityEvent(anyString(), anyString(), anyString());
        second.accept(new CodingAgentActivity(Kind.EDIT, "a.css"));
    }

    @Test
    void 프리뷰를_다시_띄우는_동안도_한_줄을_남긴다() {
        log.previewStarting("task-1");
        verify(taskStore).appendActivityEvent("task-1", "PREVIEW_STARTING", "바뀐 코드로 프리뷰를 띄우고 있습니다");

        doThrow(new IllegalStateException("db")).when(taskStore).appendActivityEvent(anyString(), anyString(), anyString());
        log.previewStarting("task-1");
    }
}
