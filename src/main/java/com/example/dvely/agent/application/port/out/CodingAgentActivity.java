package com.example.dvely.agent.application.port.out;

import java.util.Objects;

/**
 * 코딩 에이전트가 도는 동안 한 일 하나(#600). CLI 의 줄 단위 이벤트에서 읽는다.
 *
 * <p>무엇을 했는지만 담는다 — 파일 내용·명령 출력·도구 결과는 담지 않는다. 화면에 "지금 이걸 하고 있다" 를
 * 보이는 데는 대상 하나면 되고, 내용까지 실으면 사용자 저장소의 코드가 이벤트 표에 쌓인다.</p>
 *
 * @param kind   한 일의 종류
 * @param target 대상 — 작업 공간 기준 파일 경로, 명령 첫 줄, 찾은 말, 에이전트의 중간 말. 이미 짧게 자르고 비밀을 가린 값
 */
public record CodingAgentActivity(Kind kind, String target) {

    public enum Kind {
        /** 파일을 읽었다 */
        READ,
        /** 파일을 고치거나 만들었다 */
        EDIT,
        /** 셸 명령을 실행했다 */
        COMMAND,
        /** 파일·글을 찾았다 */
        SEARCH,
        /** 에이전트가 중간에 남긴 말 */
        MESSAGE
    }

    public CodingAgentActivity {
        Objects.requireNonNull(kind, "kind");
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target은 비어 있을 수 없습니다.");
        }
    }
}
