package com.example.dvely.approval.domain.value;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApprovalTypeLabelTest {

    @Test
    void everyTypeHasAReadableNameThatIsNotItsEnumName() {
        // 이름이 원시 값으로 떨어지면 #457 이 되돌아온다 — 채팅에 "CHANGE" 가 다시 보인다.
        for (ApprovalType type : ApprovalType.values()) {
            assertThat(ApprovalTypeLabel.of(type))
                    .withFailMessage("%s 에 사람이 읽는 이름이 없습니다", type)
                    .isNotBlank()
                    .isNotEqualTo(type.name())
                    .doesNotContainPattern("[A-Z_]{4,}");
        }
    }
}
