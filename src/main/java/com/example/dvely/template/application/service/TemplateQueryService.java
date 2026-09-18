package com.example.dvely.template.application.service;

import com.example.dvely.common.exception.NotFoundException;
import com.example.dvely.template.application.port.out.TemplateCatalogPort;
import com.example.dvely.template.domain.model.Template;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TemplateQueryService {

    private final TemplateCatalogPort templateCatalogPort;

    public List<Template> getTemplates() {
        return templateCatalogPort.findAll();
    }

    public Template getTemplate(String templateId) {
        return templateCatalogPort.findById(templateId)
                .orElseThrow(() -> new NotFoundException("템플릿을 찾을 수 없습니다: " + templateId));
    }
}
