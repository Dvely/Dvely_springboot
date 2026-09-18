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
            description = "고를 수 있는 템플릿 전체를 반환합니다. demoUrl 은 iframe 으로 띄워 조작해볼 수 있습니다."
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
