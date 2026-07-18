package com.example.dvely.domainbinding.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "custom_domain 연결 시 사용자가 외부 DNS provider에 등록해야 하는 레코드 안내")
public record VerificationGuideResponse(
        @Schema(description = "검증 대상 hostname", example = "www.example.com") String hostname,
        @Schema(description = "검증 방식", allowableValues = {"CNAME", "A"}) String verificationMethod,
        List<Record> records
) {

    @Schema(description = "등록해야 하는 DNS 레코드 1건")
    public record Record(
            @Schema(description = "레코드 타입", example = "CNAME") String type,
            @Schema(description = "레코드 host(서브도메인 부분)", example = "www") String host,
            @Schema(description = "레코드 값") String value
    ) {
    }
}
