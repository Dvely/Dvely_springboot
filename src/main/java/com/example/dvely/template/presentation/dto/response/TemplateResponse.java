package com.example.dvely.template.presentation.dto.response;

import com.example.dvely.template.domain.model.Template;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "퍼블리싱 템플릿. 정본은 템플릿 저장소가 발행하는 catalog.json 이고 서버는 그것을 읽어 나른다.")
public record TemplateResponse(
        @Schema(description = "템플릿 ID. 프로젝트 생성 시 templateType 에 그대로 넣는다", example = "landing-minimal")
        String templateId,

        @Schema(description = "표시 이름", example = "미니멀 랜딩")
        String name,

        @Schema(description = "한 줄 설명")
        String description,

        @Schema(description = "분류 태그")
        List<String> tags,

        @Schema(description = "기술 스택", example = "vanilla")
        String stack,

        @Schema(description = "고르기 전에 조작해보는 데모 URL. iframe 으로 띄울 수 있다")
        String demoUrl,

        @Schema(description = "바꿔도 되는 '내용'이 어디인지에 대한 템플릿 자신의 선언")
        List<ContentHintResponse> contentHints
) {

    @Schema(description = "수정 지점 힌트")
    public record ContentHintResponse(
            @Schema(description = "힌트 키", example = "hero.title") String key,
            @Schema(description = "해당 파일", example = "index.html") String where,
            @Schema(description = "무엇인지") String desc
    ) {
    }

    /**
     * sourceUrl 은 담지 않는다. 씨앗 tarball 은 서버가 컨테이너에 풀 때만 쓰는 내부 경로이고,
     * 클라이언트가 알아야 할 이유가 없다.
     */
    public static TemplateResponse from(Template template) {
        List<ContentHintResponse> hints = template.contentHints() == null
                ? List.of()
                : template.contentHints().stream()
                        .map(hint -> new ContentHintResponse(hint.key(), hint.where(), hint.desc()))
                        .toList();

        return new TemplateResponse(
                template.id(),
                template.name(),
                template.description(),
                template.tags() == null ? List.of() : template.tags(),
                template.stack(),
                template.demoUrl(),
                hints
        );
    }
}
