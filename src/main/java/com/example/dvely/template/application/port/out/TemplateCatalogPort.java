package com.example.dvely.template.application.port.out;

import com.example.dvely.template.domain.model.Template;
import java.util.List;
import java.util.Optional;

public interface TemplateCatalogPort {

    /** 카탈로그 전체. 발행 순서를 유지한다. */
    List<Template> findAll();

    Optional<Template> findById(String templateId);
}
