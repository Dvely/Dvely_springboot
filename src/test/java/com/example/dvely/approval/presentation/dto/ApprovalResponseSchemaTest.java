package com.example.dvely.approval.presentation.dto;

import com.example.dvely.approval.domain.value.ApprovalType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalResponseSchemaTest {

    // type 은 String 이라 allowableValues 는 손으로 적은 목록이다. 새 유형이 생겨도 아무도 안 깨져서
    // 여섯 개에서 멈춰 있었고, FE 가 운영 OpenAPI 를 보고 DOMAIN_UNBIND 가 아직 안 나간다고 읽었다.
    @Test
    void type_의_허용값은_ApprovalType_전부와_같다() throws Exception {
        Schema schema = ApprovalResponse.class.getDeclaredField("type").getAnnotation(Schema.class);

        assertThat(schema).isNotNull();
        assertThat(schema.allowableValues())
                .containsExactlyInAnyOrder(Arrays.stream(ApprovalType.values()).map(Enum::name).toArray(String[]::new));
    }
}
