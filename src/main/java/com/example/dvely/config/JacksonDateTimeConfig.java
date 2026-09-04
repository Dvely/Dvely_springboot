package com.example.dvely.config;

import java.time.LocalDateTime;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;

/**
 * 응답 직렬화 시 모든 LocalDateTime 에 KST 오프셋을 붙인다. FE 가 시간대와 무관하게
 * 시각을 파싱하게 하려는 것으로, 이미 OffsetDateTime 을 쓰는 DTO 들과 표기가 정합해진다.
 * 저장/복원 payload(plan_json 등)에는 LocalDateTime 이 없어 실제 영향 범위는 HTTP 응답이다.
 * Boot 4 의 HTTP 매퍼는 Jackson 3(tools.jackson)라 JsonMapperBuilderCustomizer 로 주입한다.
 */
@Configuration
public class JacksonDateTimeConfig {

    @Bean
    public JsonMapperBuilderCustomizer kstLocalDateTimeCustomizer() {
        SimpleModule module = new SimpleModule("KstLocalDateTimeModule");
        module.addSerializer(LocalDateTime.class, new KstLocalDateTimeSerializer());
        return builder -> builder.addModule(module);
    }
}
