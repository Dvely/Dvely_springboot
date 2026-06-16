package com.example.dvely.agent.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildFailureAnalyzerTest {

    private final BuildFailureAnalyzer analyzer = new BuildFailureAnalyzer();

    @Test
    void explainsMissingDependencyInUserLanguage() {
        BuildFailureAnalyzer.Analysis result = analyzer.analyze(
                "Module not found: Error: Can't resolve '@tanstack/react-query'"
        );

        assertThat(result.userMessage()).contains("모듈");
        assertThat(result.suggestedFix()).contains("dependency", "import");
        assertThat(result.logExcerpt()).contains("@tanstack/react-query");
    }

    @Test
    void limitsLogExcerptLength() {
        BuildFailureAnalyzer.Analysis result = analyzer.analyze("x".repeat(5000));

        assertThat(result.logExcerpt()).hasSize(4000);
    }
}
