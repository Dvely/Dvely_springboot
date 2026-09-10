package com.example.dvely.template.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.dvely.template.application.port.out.TemplateCatalogPort;
import com.example.dvely.template.domain.model.Template;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateCatalogGuardTest {

    @Mock
    private TemplateCatalogPort templateCatalogPort;

    private TemplateCatalogGuard guard() {
        return new TemplateCatalogGuard(templateCatalogPort);
    }

    @Test
    @DisplayName("카탈로그에 있는 ID 는 통과한다")
    void passesKnownTemplate() {
        when(templateCatalogPort.findById("landing-minimal")).thenReturn(Optional.of(
                new Template("landing-minimal", "미니멀 랜딩", "설명", List.of(), "vanilla",
                        "index.html", List.of(), "demo", "src")));

        assertThatCode(() -> guard().ensureExists("landing-minimal")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("카탈로그에 없는 ID 는 400 으로 거절한다 — 예전에는 형식만 맞으면 통과했다")
    void rejectsUnknownTemplate() {
        when(templateCatalogPort.findById("made-up")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard().ensureExists("made-up"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("made-up");
    }

    @Test
    @DisplayName("blank 로 시작한 프로젝트는 카탈로그를 건드리지 않는다")
    void skipsWhenNoTemplate() {
        assertThatCode(() -> guard().ensureExists(null)).doesNotThrowAnyException();
        assertThatCode(() -> guard().ensureExists("  ")).doesNotThrowAnyException();

        verifyNoInteractions(templateCatalogPort);
    }
}
