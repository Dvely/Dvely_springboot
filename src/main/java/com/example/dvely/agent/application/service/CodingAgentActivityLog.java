package com.example.dvely.agent.application.service;

import com.example.dvely.agent.application.port.out.CodingAgentActivity;
import com.example.dvely.agent.infrastructure.store.TaskStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 코딩 에이전트(Claude Code)가 도는 동안 한 일을 태스크 이벤트로 남긴다(#600).
 *
 * <p>여태 CODE 단계는 "코드를 작성하고 있습니다" 한 줄 뒤로 끝날 때까지 조용했다. 프리뷰에서 짚고 "더 크게" 를
 * 보내면 에이전트는 파일을 읽고 고치는데, 화면은 그동안 멈춘 것과 구분되지 않았다. 이벤트는 기존 SSE 가
 * 그대로 내보내고, 화면(타임라인)은 글이 있는 이벤트를 모두 그리므로 새 줄이 바로 보인다.</p>
 *
 * <p>한 실행에 {@link #MAX_EVENTS} 줄까지 남긴다. 한 일이 수백 개인 실행도 있어 이벤트 표와 화면을 밀어내지
 * 않게, 넘으면 한 번 알리고 그친다. 바로 앞과 같은 줄(같은 파일을 연달아 읽기)은 다시 남기지 않는다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodingAgentActivityLog {

    static final int MAX_EVENTS = 60;

    private final TaskStore taskStore;

    /** 이 태스크의 실행 하나에 쓸 받는 쪽. 실행마다 새로 만든다 — 줄 수와 바로 앞 줄을 실행마다 센다 */
    public Consumer<CodingAgentActivity> forTask(String taskId) {
        AtomicInteger written = new AtomicInteger();
        AtomicReference<String> previous = new AtomicReference<>();
        return activity -> {
            try {
                String message = messageOf(activity);
                if (message.equals(previous.getAndSet(message))) {
                    return;
                }
                int index = written.incrementAndGet();
                if (index < MAX_EVENTS) {
                    taskStore.appendActivityEvent(taskId, typeOf(activity.kind()), message);
                } else if (index == MAX_EVENTS) {
                    taskStore.appendActivityEvent(taskId, "CODING_MORE", "진행 줄이 많아 이후는 줄여 보입니다. 끝나면 결과를 알립니다.");
                }
            } catch (RuntimeException e) {
                log.debug("코딩 에이전트 진행 줄을 남기지 못했다(실행은 계속): taskId={} {}", taskId, e.toString());
            }
        };
    }

    /**
     * 코드 작업이 끝나고 프리뷰를 다시 띄우는 동안의 한 줄. 정적 앱도 서버를 받아 오고 준비를 확인하느라 몇 초가 걸리고,
     * 빌드가 있는 앱은 더 걸린다 — 그동안 마지막 줄이 에이전트의 맺음말이면 다 끝났는데 멈춘 것처럼 보인다.
     */
    public void previewStarting(String taskId) {
        try {
            taskStore.appendActivityEvent(taskId, "PREVIEW_STARTING", "바뀐 코드로 프리뷰를 띄우고 있습니다");
        } catch (RuntimeException e) {
            log.debug("프리뷰 준비 줄을 남기지 못했다(작업은 계속): taskId={} {}", taskId, e.toString());
        }
    }

    static String typeOf(CodingAgentActivity.Kind kind) {
        return "CODING_" + kind.name();
    }

    /** 화면은 이 글을 그대로 보인다. 파일 이름이 라틴 문자라 조사를 붙이지 않는 꼴로 쓴다 */
    static String messageOf(CodingAgentActivity activity) {
        return switch (activity.kind()) {
            case READ -> "파일 읽기 · " + activity.target();
            case EDIT -> "파일 고치기 · " + activity.target();
            case COMMAND -> "명령 실행 · " + activity.target();
            case SEARCH -> "찾기 · " + activity.target();
            case MESSAGE -> "에이전트: " + activity.target();
        };
    }
}
