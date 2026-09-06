package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 웹 이미지 빌드의 사용자 입력(frontendRepo·frontendDir) 검증. 이 값들은 컨테이너 셸 명령(clone·test·
 * tar·파일기록)에 들어가므로, 셸 메타문자·상위경로 이탈을 진입점에서 막아 명령 주입을 차단한다.
 */
class WebImageBuildValidationTest {

    @Test
    @DisplayName("frontendRepo: owner/repo 만 통과, 셸 메타문자·.. 는 거부")
    void frontendRepo() {
        assertThatCode(() -> WebImageBuildService.validateFrontendRepo(null)).doesNotThrowAnyException();
        assertThatCode(() -> WebImageBuildService.validateFrontendRepo("dldnsgkr/dvely-fe")).doesNotThrowAnyException();
        for (String bad : new String[]{
                "a/b; rm -rf /", "a/b`whoami`", "a/b$(id)", "a/b|c", "a b/c", "../../etc", "a/b && x", "notaslug"}) {
            assertThatThrownBy(() -> WebImageBuildService.validateFrontendRepo(bad))
                    .as("거부: %s", bad).isInstanceOf(BackendBuildException.class);
        }
    }

    @Test
    @DisplayName("frontendDir: 안전한 하위경로만 통과, 셸 메타문자·.. 는 거부")
    void frontendDir() {
        assertThatCode(() -> WebImageBuildService.validateFrontendDir(null)).doesNotThrowAnyException();
        assertThatCode(() -> WebImageBuildService.validateFrontendDir("frontend")).doesNotThrowAnyException();
        assertThatCode(() -> WebImageBuildService.validateFrontendDir("apps/web")).doesNotThrowAnyException();
        for (String bad : new String[]{
                "web; curl evil", "web`x`", "$(x)", "a|b", "a b", "../secrets", "web && rm", "web\nx"}) {
            assertThatThrownBy(() -> WebImageBuildService.validateFrontendDir(bad))
                    .as("거부: %s", bad).isInstanceOf(BackendBuildException.class);
        }
    }
}
