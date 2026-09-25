package com.example.dvely.template.presentation;

import com.example.dvely.template.application.service.TemplateQueryService;
import com.example.dvely.template.presentation.dto.response.TemplateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Template", description = "퍼블리싱 템플릿 카탈로그 API")
@RestController
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateQueryService templateQueryService;

    @Operation(
            summary = "템플릿 목록 조회",
            description = "고를 수 있는 템플릿 전체를 반환합니다. 목록 카드에는 thumbnailUrl 을 쓰고, "
                          + "고른 하나만 demoUrl 을 iframe 으로 띄워 조작해보게 하는 것이 의도된 사용입니다 "
                          + "(17종을 동시에 iframe 으로 올리면 데모 이미지 총량이 한 번에 갑니다)."
    )
    @GetMapping("/api/v1/templates")
    public List<TemplateResponse> getTemplates() {
        return templateQueryService.getTemplates().stream()
                .map(TemplateResponse::from)
                .toList();
    }

    @Operation(
            summary = "템플릿 단건 조회",
            description = "템플릿 하나의 상세와 contentHints 를 반환합니다."
    )
    @GetMapping("/api/v1/templates/{templateId}")
    public TemplateResponse getTemplate(@PathVariable String templateId) {
        return TemplateResponse.from(templateQueryService.getTemplate(templateId));
    }
}
