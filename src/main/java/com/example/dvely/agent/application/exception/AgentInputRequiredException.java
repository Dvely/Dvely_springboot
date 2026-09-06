package com.example.dvely.agent.application.exception;

import com.example.dvely.agent.application.dto.ClarificationRequest;

/**
 * 스텝이 진행하려면 사용자 입력이 필요할 때 던진다 → 실행기가 WAITING_INPUT 으로 전이한다.
 *
 * <p>두 형태가 있다: DEPLOY(레포 이름)·DOMAIN_BIND(도메인)는 <b>단순 텍스트 질문</b>(메시지 문자열만).
 * CLARIFY 는 <b>구조화 질문</b>({@link ClarificationRequest} — 입력 타입·선택지)을 담아, FE 가 라디오/
 * 체크박스/텍스트를 렌더할 수 있게 한다. {@link #getClarification()} 이 null 이면 단순 텍스트 입력이다.</p>
 */
public class AgentInputRequiredException extends RuntimeException {

    private final transient ClarificationRequest clarification;

    public AgentInputRequiredException(String question) {
        super(question);
        this.clarification = null;
    }

    public AgentInputRequiredException(ClarificationRequest clarification) {
        super(clarification.question());
        this.clarification = clarification;
    }

    /** 구조화 질문(CLARIFY). 단순 텍스트 입력이면 null. */
    public ClarificationRequest getClarification() {
        return clarification;
    }
}
