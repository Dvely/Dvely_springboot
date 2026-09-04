package com.example.dvely.config;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * LocalDateTime 을 KST(+09:00) 오프셋을 붙여 ISO-8601 로 직렬화한다.
 * 저장된 시각은 모두 KST 벽시계라, 오프셋 없이 내보내면 다른 시간대 클라이언트가 자기
 * 로컬로 잘못 해석한다. 오프셋을 붙이면 어느 시간대에서 파싱해도 같은 순간이 된다.
 */
public class KstLocalDateTimeSerializer extends ValueSerializer<LocalDateTime> {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt)
            throws JacksonException {
        gen.writeString(value.atOffset(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
}
