package com.example.dvely.agent.application.dto;

import java.util.List;

/**
 * 빌드 전에 사용자에게 되묻는 <b>구조화 질문</b>. 요청이 핵심 스펙(특히 백엔드 스택, 또는 매우 모호한
 * 범위)에서 추측해야만 하는 애매함을 가질 때 {@code AgentType.CLARIFY} 스텝이 이걸 내고, 실행기가
 * WAITING_INPUT 으로 사용자에게 노출한다.
 *
 * <p>답 형식은 질문마다 다르다: 열린 답은 {@code TEXT}(입력창), 택1은 {@code SINGLE_SELECT}(라디오),
 * 여러 개는 {@code MULTI_SELECT}(체크박스). FE 가 {@code inputType} 을 보고 컨트롤을 렌더한다.
 * <b>구조는 "질문"에만</b> 있고, 사용자 답은 사람이 읽는 문자열 하나로 돌아온다(기존 {@code /input}
 * 계약 그대로) — 그 문자열을 재-decide(LLM)가 읽어 일관된 플랜을 다시 만든다.</p>
 */
public record ClarificationRequest(
        String question,
        InputType inputType,
        List<Option> options,
        boolean allowOther
) {

    public enum InputType {
        /** 자유 입력(입력창). options 없음. */
        TEXT,
        /** 택1(라디오). 상호배타 선택. */
        SINGLE_SELECT,
        /** 여러 개(체크박스). */
        MULTI_SELECT
    }

    /** 선택지 하나. {@code recommended} 면 FE 가 권장안으로 강조한다. */
    public record Option(String value, String label, boolean recommended) {}
}
