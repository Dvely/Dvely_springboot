package com.example.dvely.approval.application.result;

/**
 * 승인할 때 값을 함께 받아야 하는 승인의 입력 스펙.
 *
 * 이게 null 이면 단순 승인/거절이고, 있으면 FE 가 입력 필드를 그린 뒤 그 값을
 * POST /approvals/{id}/approve 의 본문에 field 이름으로 실어 보내면 된다.
 *
 * 버튼 문구 같은 표시용 텍스트는 담지 않는다. 서버가 한글 문구를 박으면 다국어를 넣을 때
 * 서버를 고쳐야 하고, 문구는 화면 맥락에 따라 달라지는 것이 자연스럽다. 여기 담는 것은
 * "무엇을 어떤 형식으로 받아야 하는가"까지다.
 *
 * @param field        approve 요청 본문의 키
 * @param defaultValue 입력창에 미리 채워둘 값. 비운 채 승인하면 서버도 이 값을 쓴다
 * @param required     true 면 값이 반드시 있어야 한다. false 면 비워도 defaultValue 로 진행
 * @param pattern      값이 만족해야 하는 정규식. 서버도 같은 규칙으로 정규화한다
 * @param maxLength    최대 길이
 */
public record ApprovalInput(
        String field,
        String defaultValue,
        boolean required,
        String pattern,
        Integer maxLength
) {
}
