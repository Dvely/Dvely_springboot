package com.example.dvely.template.application.service;

import com.example.dvely.template.application.port.out.TemplateCatalogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 프로젝트에 붙는 templateType 이 실제로 카탈로그에 있는 값인지 확인한다.
 *
 * 이 확인이 없던 동안 templateType 은 슬러그 형식만 통과하면 무엇이든 저장됐다. 값을 읽는 코드가
 * 없어서 아무도 몰랐을 뿐이고, 씨딩이 붙는 순간부터는 "고를 때는 200, 만들 때는 실패"가 된다.
 */
@Service
@RequiredArgsConstructor
public class TemplateCatalogGuard {

    private final TemplateCatalogPort templateCatalogPort;

    /**
     * @param templateId 정규화된 템플릿 ID. blank 로 시작한 프로젝트는 null 이며 확인할 것이 없다
     */
    public void ensureExists(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return;
        }
        if (templateCatalogPort.findById(templateId).isEmpty()) {
            throw new IllegalArgumentException("존재하지 않는 템플릿입니다: " + templateId);
        }
    }
}
