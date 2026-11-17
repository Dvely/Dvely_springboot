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

    // ------------------------------------------------------------------ 범위 사다리 (§15.3, #694)

    @Test
    void 범위_사다리는_아는_종류의_칸만_6칸까지_글은_60자로_받는다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p3","ok":true,"element":{"type":"button","text":"저장"},"defaultScope":9,
                 "scopes":[{"kind":"element","type":"button","text":"저장","bounds":{"x":0.1,"y":0.1,"w":0.1,"h":0.1},"onclick":"x"},
                           {"kind":"group","type":"form","text":"%s","bounds":{"x":0,"y":0,"w":0.5,"h":0.5}},
                           {"kind":"telepathy","bounds":{"x":0,"y":0,"w":1,"h":1}},
                           {"kind":"group","type":"div"},
                           {"kind":"component","type":"div","component":"A","bounds":{"x":0,"y":0,"w":0.6,"h":0.6}},
                           {"kind":"component","type":"div","component":"B","bounds":{"x":0,"y":0,"w":0.7,"h":0.7}},
                           {"kind":"component","type":"div","component":"C","bounds":{"x":0,"y":0,"w":0.8,"h":0.8}},
                           {"kind":"component","type":"div","component":"D","bounds":{"x":0,"y":0,"w":0.85,"h":0.85}},
                           {"kind":"screen","type":"screen","bounds":{"x":0.2,"y":0.2,"w":0.1,"h":0.1}}]}
                """.formatted("가".repeat(100)))).orElseThrow();

        JsonNode scopes = r.description().path("scopes");
        assertThat(scopes).hasSize(6);
        assertThat(scopes.findValuesAsText("kind")).containsExactly("element", "group", "component", "component", "component", "component");
        assertThat(scopes.get(0).has("onclick")).isFalse();
        assertThat(scopes.get(1).path("text").asText()).hasSize(60);
        assertThat(r.description().path("defaultScope").asInt()).as("범위 밖이면 0").isZero();
    }

    @Test
    void 소스_후보의_칸_번호는_1에서_5까지만_남긴다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p6","ok":true,"element":{"type":"Button"},
                 "sourceHints":[{"file":"A.swift","line":3,"reason":"ID","scope":1},
                                {"file":"B.swift","reason":"ID","scope":0},
                                {"file":"C.swift","reason":"ID","scope":9},
                                {"file":"D.swift","reason":"ID","scope":"2"}]}
                """)).orElseThrow();

        JsonNode hints = r.description().path("sourceHints");
        assertThat(hints.get(0).path("scope").asInt()).isEqualTo(1);
        assertThat(hints.get(1).has("scope")).isFalse();
        assertThat(hints.get(2).has("scope")).isFalse();
        assertThat(hints.get(3).has("scope")).isFalse();
    }

    @Test
    void 화면_전체만_온_결과도_찾은_것이고_화면_칸의_테두리는_늘_화면_전체다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p4","ok":true,"screen":{"label":"/"},
                 "scopes":[{"kind":"screen","type":"screen","bounds":{"x":0.3,"y":0.3,"w":0.1,"h":0.1}}],"defaultScope":0}
                """)).orElseThrow();

        assertThat(r.resolved()).isTrue();
        assertThat(r.reason()).isNull();
        assertThat(r.label()).isEqualTo("화면 전체");
        assertThat(r.bounds()).isNull();
        assertThat(r.description().path("scopes").get(0).path("bounds").path("w").asDouble()).isEqualTo(1.0);
        assertThat(r.description().has("element")).isFalse();
    }

    @Test
    void 찾지_못한_결과에는_범위_사다리를_싣지_않는다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p5","ok":false,"reason":"NO_ELEMENT",
                 "scopes":[{"kind":"screen","type":"screen","bounds":{"x":0,"y":0,"w":1,"h":1}}]}
                """)).orElseThrow();

        assertThat(r.resolved()).isFalse();
        assertThat(r.description().has("scopes")).isFalse();
    }

    @Test
    void 고른_범위의_이름은_요소_칸이면_요소_이름_그_밖이면_칸_이름이다() throws Exception {
        String ladder = """
                "element":{"type":"button","text":"저장"},
                "scopes":[{"kind":"element","type":"button","text":"저장"},
                          {"kind":"component","type":"form","id":"profile","component":"ProfileForm"},
                          {"kind":"group","type":"li","text":"요금제"},
                          {"kind":"screen","type":"screen"}]""";

        assertThat(PinResultSanitizer.selectedLabel(json.readTree("{" + ladder + ",\"scope\":0}"))).isEqualTo("<button> \"저장\"");
        assertThat(PinResultSanitizer.selectedLabel(json.readTree("{" + ladder + ",\"scope\":1}"))).isEqualTo("컴포넌트 ProfileForm");
        assertThat(PinResultSanitizer.selectedLabel(json.readTree("{" + ladder + ",\"scope\":2}"))).isEqualTo("항목 \"요금제\"");
        assertThat(PinResultSanitizer.selectedLabel(json.readTree("{" + ladder + ",\"scope\":3}"))).isEqualTo("화면 전체");
        assertThat(PinResultSanitizer.selectedLabel(json.readTree("{" + ladder + ",\"scope\":7}"))).as("범위 밖이면 0번").isEqualTo("<button> \"저장\"");
        assertThat(PinResultSanitizer.selectedLabel(json.readTree("{\"element\":{\"type\":\"button\",\"text\":\"저장\"}}")))
                .as("사다리 없는 결과(§14)").isEqualTo("<button> \"저장\"");
        assertThat(PinResultSanitizer.selectedLabel(json.readTree("{\"ok\":false}"))).isNull();
    }

    @Test
    void 끈_영역은_가운데_요소가_없어도_찾은_것이고_안의_요소는_12개_글은_60자() throws Exception {
        StringBuilder members = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            members.append(i == 0 ? "" : ",").append("{\"type\":\"li\",\"text\":\"").append("가".repeat(80))
                    .append("\",\"bounds\":{\"x\":0.1,\"y\":0.1,\"w\":0.1,\"h\":0.01},\"value\":\"x\"}");
        }
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p7","ok":true,"empty":false,
                 "scopes":[{"kind":"region","type":"region","members":99,"bounds":{"x":0.1,"y":0.1,"w":0.5,"h":0.5}},
                           {"kind":"screen","type":"screen"}],
                 "members":[%s,{"type":"p","text":"테두리 없음"}]}
                """.formatted(members))).orElseThrow();

        assertThat(r.resolved()).isTrue();
        assertThat(r.label()).isEqualTo("영역 · 요소 12개");
        JsonNode d = r.description();
        assertThat(d.path("members")).hasSize(12);
        assertThat(d.path("members").get(0).path("text").asText()).hasSize(60);
        assertThat(d.path("members").get(0).has("value")).isFalse();
        assertThat(d.path("empty").asBoolean()).isFalse();
        assertThat(PinResultSanitizer.scopeLabel(json.readTree("{\"kind\":\"region\",\"members\":0}"), false)).isEqualTo("빈 자리");
        assertThat(PinResultSanitizer.scopeLabel(json.readTree("{\"kind\":\"region\"}"), false)).isEqualTo("영역");
    }

    @Test
    void 비었는지는_기기의_값을_따른다_목록이_덜어졌어도() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p9","ok":true,"empty":false,
                 "scopes":[{"kind":"region","type":"region","members":7,"bounds":{"x":0.1,"y":0.1,"w":0.5,"h":0.5}}]}
                """)).orElseThrow();
        assertThat(r.description().path("empty").asBoolean()).isFalse();
        assertThat(r.label()).isEqualTo("영역 · 요소 7개");
    }

    @Test
    void 빈_자리_둘레는_네_방향만_4개까지이고_실패한_짚기에도_남는다() throws Exception {
        PinResolution r = sanitizer.sanitize(json.readTree("""
                {"probeId":"p8","ok":false,"reason":"NO_ELEMENT",
                 "near":[{"dir":"up","type":"h2","text":"요금제","bounds":{"x":0,"y":0,"w":0.2,"h":0.05},"distance":0.04},
                         {"dir":"north","type":"x","bounds":{"x":0,"y":0,"w":0.2,"h":0.05}},
                         {"dir":"down","type":"footer","distance":0.1},
                         {"dir":"left","type":"nav","bounds":{"x":0,"y":0,"w":0.1,"h":0.5},"distance":7}]}
                """)).orElseThrow();

        JsonNode near = r.description().path("near");
        assertThat(near.findValuesAsText("dir")).containsExactly("up", "left");
        assertThat(near.get(1).path("distance").asDouble()).as("비율로 자른다").isEqualTo(1.0);
        assertThat(PinResultSanitizer.nearLabel(near.get(0))).isEqualTo("위 <h2> \"요금제\"");
    }

    @Test
    void 덩어리_이름은_태그와_역할을_사람이_부르는_말로_옮긴다() {
        assertThat(PinResultSanitizer.groupWord("form")).isEqualTo("폼");
        assertThat(PinResultSanitizer.groupWord("navigation")).isEqualTo("메뉴");
        assertThat(PinResultSanitizer.groupWord("UL")).isEqualTo("목록");
        assertThat(PinResultSanitizer.groupWord("listitem")).isEqualTo("항목");
        assertThat(PinResultSanitizer.groupWord("div")).isEqualTo("<div>");
        // Android 뷰 클래스·iOS 접근성 종류(#694 PR-3)
        assertThat(PinResultSanitizer.groupWord("LinearLayout")).isEqualTo("묶음");
        assertThat(PinResultSanitizer.groupWord("RecyclerView")).isEqualTo("목록");
        assertThat(PinResultSanitizer.groupWord("MaterialCardView")).isEqualTo("카드");
        assertThat(PinResultSanitizer.groupWord("Group")).isEqualTo("묶음");
        assertThat(PinResultSanitizer.groupWord("Cell")).isEqualTo("항목");
        assertThat(PinResultSanitizer.groupWord("Table")).isEqualTo("목록");
        assertThat(PinResultSanitizer.groupWord("fieldset")).isEqualTo("입력 묶음");
        assertThat(PinResultSanitizer.groupWord("")).isEqualTo("덩어리");
    }

    @Test
    void 여러_곳이면_첫_곳의_이름과_개수로_부르고_곳마다_고른_칸이_있다() throws Exception {
        com.fasterxml.jackson.databind.JsonNode description = new ObjectMapper().readTree("""
                {"element":{"type":"button","text":"저장"},"scope":0,
                 "scopes":[{"kind":"element","type":"button","text":"저장","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}}],
                 "marks":[{"n":2,"scope":1,"probe":{"element":{"type":"h2","text":"요금제"},
                   "scopes":[{"kind":"element","type":"h2","bounds":{"x":0,"y":0,"w":0.1,"h":0.1}},
                             {"kind":"screen","bounds":{"x":0,"y":0,"w":1,"h":1}}]}},
                  {"n":3,"probe":{"ok":false,"reason":"NO_ELEMENT"}}]}
                """);

        assertThat(PinResultSanitizer.selectedLabel(description)).isEqualTo("① <button> \"저장\" 외 2곳");
        assertThat(PinResultSanitizer.places(description)).hasSize(3);
        assertThat(PinResultSanitizer.placeLabel(PinResultSanitizer.places(description).get(1))).isEqualTo("화면 전체");
        assertThat(PinResultSanitizer.placeLabel(PinResultSanitizer.places(description).get(2))).isNull();
    }
}
