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
 *
 * <p>{@code actionType} 이 있으면 이건 질문이 아니라 <b>필요 조치</b>다 — FE 는 입력 컨트롤 대신 그 조치
 * UI 를 띄운다. {@code CONNECT_CLOUD} 는 운영 배포에 클라우드 연결이 없을 때 나오며, FE 가 연결 가이드
 * (GET /api/v1/cloud-connections/requirements)를 프리뷰 위로 슬라이드해 띄운다. 사용자가 연결한 뒤
 * "다시 시도"를 {@code /input} 으로 보내면 그 스텝이 재실행되어 연결을 다시 확인한다.</p>
 */
public record ClarificationRequest(
        String question,
        InputType inputType,
        List<Option> options,
        boolean allowOther,
        ActionType actionType
) {

    /** actionType 없는 일반 구조화 질문(기존 계약). */
    public ClarificationRequest(String question, InputType inputType, List<Option> options, boolean allowOther) {
        this(question, inputType, options, allowOther, null);
    }

    public enum InputType {
        /** 자유 입력(입력창). options 없음. */
        TEXT,
        /** 택1(라디오). 상호배타 선택. */
        SINGLE_SELECT,
        /** 여러 개(체크박스). */
        MULTI_SELECT
    }

    /** 질문이 아니라 사용자가 밟아야 할 조치. FE 가 그에 맞는 UI 를 띄운다. */
    public enum ActionType {
        /** 운영 배포에 클라우드 연결이 없음 → FE 가 연결 가이드를 띄운다. */
        CONNECT_CLOUD
    }

    /** 선택지 하나. {@code recommended} 면 FE 가 권장안으로 강조한다. */
    public record Option(String value, String label, boolean recommended) {}

    /** 클라우드 연결 필요 조치. FE 가 질문 대신 연결 가이드를 슬라이드해 띄운다. */
    public static ClarificationRequest connectCloud(String question) {
        return new ClarificationRequest(question, InputType.TEXT, List.of(), false, ActionType.CONNECT_CLOUD);
    }
}
