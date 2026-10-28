package com.example.dvely.change.application.result;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** HTTP 응답은 Jackson 3 로 나간다. 계약의 키 이름 "default" 가 그대로 나가는지 본다. */
class ResultScopeJsonTest {

    @Test
    void rejectOptionDefaultIsSerializedUnderTheContractName() {
        String json = JsonMapper.builder().build().writeValueAsString(
                new ResultScope.RejectOptionView("KEEP", "preview 에 남기기", "설명", true, true, null));

        assertThat(json).contains("\"default\":true").doesNotContain("isDefault");
    }
}
