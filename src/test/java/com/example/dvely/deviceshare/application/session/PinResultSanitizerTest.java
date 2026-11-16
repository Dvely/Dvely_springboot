package com.example.dvely.deviceshare.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 기기가 보낸 요소 기술에서 아는 필드만 골라 다시 만든다(설계 §14.3). */
class PinResultSanitizerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final PinResultSanitizer sanitizer = new PinResultSanitizer(json);

    @Test
    void 아는_필드만_남기고_뷰어용_이름과_컴포넌트를_뽑는다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p7","ok":true,"secret":"버린다",
                 "screen":{"label":"/settings","url":"http://localhost:5173/settings","title":"Vite App","cookie":"x"},
                 "point":{"x":0.42,"y":0.17},
                 "element":{"type":"button","text":"저장","id":"save","selector":"form > button","onclick":"alert(1)",
                            "bounds":{"x":0.38,"y":0.15,"w":0.12,"h":0.04}},
                 "components":[{"name":"SaveButton","file":"src/components/SaveButton.tsx","line":12},{"name":"ProfileForm"}],
                 "sourceHints":[{"file":"src/components/SaveButton.tsx","line":12,"reason":"COMPONENT"}]}
                """)).orElseThrow();

        assertThat(r.probeId()).isEqualTo("p7");
        assertThat(r.resolved()).isTrue();
        assertThat(r.reason()).isNull();
        assertThat(r.screenLabel()).isEqualTo("/settings");
        assertThat(r.label()).isEqualTo("<button> \"저장\"");
        assertThat(r.component()).isEqualTo("SaveButton");
        assertThat(r.bounds().path("w").asDouble()).isEqualTo(0.12);
        JsonNode d = r.description();
        assertThat(d.has("secret")).isFalse();
        assertThat(d.path("screen").has("cookie")).isFalse();
        assertThat(d.path("element").has("onclick")).isFalse();
        assertThat(d.path("components").get(0).path("line").asInt()).isEqualTo(12);
        assertThat(d.path("sourceHints").get(0).path("reason").asText()).isEqualTo("COMPONENT");
    }

    @Test
    void 프로젝트_밖_경로는_버린다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p1","ok":true,"element":{"type":"div"},
                 "sourceHints":[{"file":"/etc/passwd"},{"file":"../secret.txt"},{"file":"a/../../b"},{"file":"C:\\\\x"},
                                {"file":"~/.ssh/id"},{"file":"src\\\\App.tsx","line":3,"reason":"WHO"}],
                 "components":[{"name":"App","file":"/Users/me/app.tsx"}]}
                """)).orElseThrow();

        assertThat(r.description().path("sourceHints")).hasSize(1);
        JsonNode hint = r.description().path("sourceHints").get(0);
        assertThat(hint.path("file").asText()).isEqualTo("src/App.tsx");
        assertThat(hint.has("reason")).isFalse();
        assertThat(r.description().path("components").get(0).has("file")).isFalse();
    }

    @Test
    void 비율은_0과_1_사이로_자르고_글은_자르고_제어_문자를_지운다() throws Exception {
        String longText = "가".repeat(500);
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p2","ok":true,"point":{"x":-3,"y":9},
                 "element":{"type":"p","text":"%s","id":"a\\u0000b\\nc","bounds":{"x":0.9,"y":0.5,"w":0.5,"h":2}}}
                """.formatted(longText))).orElseThrow();

        JsonNode d = r.description();
        assertThat(d.path("point").path("x").asDouble()).isZero();
        assertThat(d.path("point").path("y").asDouble()).isEqualTo(1.0);
        assertThat(d.path("element").path("bounds").path("w").asDouble()).isEqualTo(0.1);
        assertThat(d.path("element").path("bounds").path("h").asDouble()).isEqualTo(0.5);
        assertThat(d.path("element").path("text").asText()).hasSize(PinResultSanitizer.MAX_TEXT);
        assertThat(d.path("element").path("id").asText()).isEqualTo("a b c");
    }

    @Test
    void 요소를_못_찾았으면_까닭과_점만_남긴다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p3","ok":false,"reason":"WHATEVER","point":{"x":0.5,"y":0.5},
                 "element":{"type":"button"},"sourceHints":[{"file":"a.ts"}]}
                """)).orElseThrow();

        assertThat(r.resolved()).isFalse();
        assertThat(r.reason()).isEqualTo("NO_ELEMENT");
        assertThat(r.label()).isNull();
        assertThat(r.bounds()).isNull();
        assertThat(r.description().has("element")).isFalse();
        assertThat(r.description().has("sourceHints")).isFalse();
        assertThat(r.description().path("ok").asBoolean()).isFalse();
    }

    @Test
    void 짚기_id_가_없거나_틀리면_받지_않는다() throws Exception {
        assertThat(sanitizer.sanitize(json.readTree("{\"ok\":true}"))).isEmpty();
        assertThat(sanitizer.sanitize(json.readTree("{\"probeId\":\"x1\"}"))).isEmpty();
        assertThat(sanitizer.sanitize(json.readTree("[]"))).isEmpty();
    }
}
