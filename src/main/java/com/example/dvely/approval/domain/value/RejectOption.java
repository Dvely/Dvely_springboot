package com.example.dvely.approval.domain.value;

/**
 * RESULT 승인을 거절할 때 preview 를 어떻게 둘지(#532).
 *
 * <ul>
 *   <li>{@link #KEEP} — preview 는 그대로 둔다. 다음 작업이 그 위에서 이어지고, 다음 결과 승인 때 함께 반영된다.</li>
 *   <li>{@link #REVERT} — preview 를 main 과 같은 내용으로 되돌리는 커밋을 올린다(강제 push 아님).</li>
 * </ul>
 */
public enum RejectOption {
    KEEP,
    REVERT
}
