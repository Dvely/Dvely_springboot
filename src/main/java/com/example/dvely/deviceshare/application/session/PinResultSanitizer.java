package com.example.dvely.deviceshare.application.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 기기가 보낸 요소 기술에서 아는 필드만 골라 다시 만든다(설계 §14.3).
 *
 * <p>기기는 주인 자신의 것이지만, 이 값은 손님 뷰어에 그려지고 에이전트 프롬프트에 들어간다. 그래서 모르는 필드는 버리고,
 * 길이를 자르고, 파일 경로는 프로젝트 안의 상대 경로만 받는다.</p>
 */
public final class PinResultSanitizer {

    static final int MAX_TEXT = 200;
    static final int MAX_SELECTOR = 300;
    static final int MAX_PATH = 300;
    static final int MAX_LABEL = 512;
    static final int MAX_URL = 1024;
    static final int MAX_NAME = 100;
    static final int MAX_TYPE = 40;
    static final int MAX_ANCESTORS = 5;
    static final int MAX_COMPONENTS = 8;
    static final int MAX_HINTS = 5;
    static final int MAX_VIEWER_LABEL = 80;
    /** §15.3: 범위 사다리는 6칸까지, 칸의 글은 60자까지 */
    static final int MAX_SCOPES = 6;
    static final int MAX_SCOPE_TEXT = 60;
    /** §15.4: 끈 영역 안의 요소 12개, 빈 자리 둘레 4개 */
    static final int MAX_MEMBERS = 12;
    static final int MAX_NEAR = 4;
    private static final Set<String> NEAR_DIRS = Set.of("up", "down", "left", "right");

    private static final Pattern PROBE_ID = Pattern.compile("p[0-9]{1,9}");
    private static final Set<String> REASONS = Set.of("NO_ELEMENT", "UNSUPPORTED", "TIMEOUT");
    private static final Set<String> HINT_REASONS = Set.of("COMPONENT", "ID", "TEXT");
    private static final String[] SCREEN_FIELDS = {"label", "url", "title", "package", "activity", "bundleId", "appName"};
    /** §15.3·§15.4·§15.7 의 칸 종류. 모르는 종류의 칸은 버린다 */
    private static final Set<String> SCOPE_KINDS = Set.of("element", "group", "component", "screen", "region", "text");

    private final ObjectMapper json;

    public PinResultSanitizer(ObjectMapper json) {
        this.json = json;
    }

    /** @return probeId 가 없거나 형식이 틀리면 빈 값(어느 짚기의 답인지 알 수 없다) */
    public Optional<PinResolution> sanitize(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return Optional.empty();
        }
        String probeId = raw.path("probeId").asText("");
        if (!PROBE_ID.matcher(probeId).matches()) {
            return Optional.empty();
        }
        ObjectNode out = json.createObjectNode();

        ObjectNode screen = json.createObjectNode();
        JsonNode rawScreen = raw.path("screen");
        for (String field : SCREEN_FIELDS) {
            String value = text(rawScreen.path(field), "url".equals(field) ? MAX_URL : "label".equals(field) ? MAX_LABEL : MAX_TEXT);
            if (value != null) {
                screen.put(field, value);
            }
        }
        if (!screen.isEmpty()) {
            out.set("screen", screen);
        }
        ObjectNode point = ratioPoint(raw.path("point"));
        if (point != null) {
            out.set("point", point);
        }

        boolean ok = raw.path("ok").asBoolean(false);
        ObjectNode element = ok ? element(raw.path("element")) : null;
        ArrayNode scopes = ok ? list(raw.path("scopes"), MAX_SCOPES, this::scope) : json.createArrayNode();
        // §15.6: 화면 전체를 짚으면 요소 없이 화면 칸 하나만 온다. 그것도 찾은 것이다
        boolean screenOnly = element == null && scopes.size() == 1 && "screen".equals(scopes.get(0).path("kind").asText());
        // §15.4: 끈 영역은 가운데에 요소가 없어도(빈 자리) 찾은 것이다
        boolean region = !scopes.isEmpty() && "region".equals(scopes.get(0).path("kind").asText());
        boolean resolved = element != null || screenOnly || region;
        if (resolved && !scopes.isEmpty()) {
            out.set("scopes", scopes);
            int defaultScope = raw.path("defaultScope").asInt(0);
            out.put("defaultScope", defaultScope >= 0 && defaultScope < scopes.size() ? defaultScope : 0);
        }
        if (resolved && region) {
            ArrayNode members = list(raw.path("members"), MAX_MEMBERS, this::member);
            if (!members.isEmpty()) {
                out.set("members", members);
            }
            // 비었는지는 기기의 값이다. 목록은 크기 때문에 덜어졌거나(§15.9), 페이지가 시간 안에 다 세지 못했을 수 있다
            out.put("empty", raw.path("empty").isBoolean() ? raw.path("empty").asBoolean() : members.isEmpty());
        }
        // 빈 자리 둘레는 끈 영역에도, 빈 곳을 탭한 실패에도 온다
        ArrayNode near = list(raw.path("near"), MAX_NEAR, this::nearItem);
        if (!near.isEmpty()) {
            out.set("near", near);
        }
        if (element != null) {
            out.set("element", element);
            ArrayNode ancestors = list(raw.path("ancestors"), MAX_ANCESTORS, this::ancestor);
            if (!ancestors.isEmpty()) {
                out.set("ancestors", ancestors);
            }
            ArrayNode components = list(raw.path("components"), MAX_COMPONENTS, this::component);
            if (!components.isEmpty()) {
                out.set("components", components);
            }
            ArrayNode hints = list(raw.path("sourceHints"), MAX_HINTS, this::sourceHint);
            if (!hints.isEmpty()) {
                out.set("sourceHints", hints);
            }
        }
        String reason = null;
        if (!resolved) {
            String rawReason = raw.path("reason").asText("");
            reason = REASONS.contains(rawReason) ? rawReason : "NO_ELEMENT";
            out.put("reason", reason);
        }
        out.put("ok", resolved);

        String screenLabel = screen.path("label").asText("");
        ObjectNode bounds = element == null ? null : (ObjectNode) element.get("bounds");
        String component = out.path("components").path(0).path("name").asText(null);
        String label = element != null ? viewerLabel(element) : screenOnly ? SCREEN_LABEL
                : region ? scopeLabel(scopes.get(0), false) : null;
        return Optional.of(new PinResolution(probeId, resolved, reason, out, screenLabel, bounds, label, component));
    }

    // ------------------------------------------------------------------ 범위 사다리 (§15.3)

    static final String SCREEN_LABEL = "화면 전체";

    private ObjectNode scope(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String kind = node.path("kind").asText("");
        ObjectNode bounds = "screen".equals(kind) ? fullScreen() : ratioRect(node.path("bounds"));
        if (!SCOPE_KINDS.contains(kind) || bounds == null || bounds.path("w").asDouble() <= 0 || bounds.path("h").asDouble() <= 0) {
            return null;
        }
        ObjectNode out = json.createObjectNode().put("kind", kind);
        putText(out, "type", node.path("type"), MAX_TYPE);
        putText(out, "text", node.path("text"), MAX_SCOPE_TEXT);
        putText(out, "id", node.path("id"), MAX_NAME);
        putText(out, "selector", node.path("selector"), MAX_SELECTOR);
        putText(out, "component", node.path("component"), MAX_NAME);
        if ("region".equals(kind) && node.path("members").isInt()) {
            out.put("members", Math.max(0, Math.min(MAX_MEMBERS, node.path("members").asInt())));
        }
        out.set("bounds", bounds);
        return out;
    }

    /** §15.4: 끈 영역 안의 요소 하나. 테두리가 있어야 하고, 글은 60자 */
    private ObjectNode member(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        ObjectNode bounds = ratioRect(node.path("bounds"));
        if (bounds == null) {
            return null;
        }
        ObjectNode out = json.createObjectNode();
        putText(out, "type", node.path("type"), MAX_TYPE);
        putText(out, "text", node.path("text"), MAX_SCOPE_TEXT);
        putText(out, "id", node.path("id"), MAX_NAME);
        out.set("bounds", bounds);
        return out;
    }

    /** §15.4: 빈 자리 둘레의 요소 하나. 방향은 위·아래·왼쪽·오른쪽, 거리는 화면 비율 */
    private ObjectNode nearItem(JsonNode node) {
        ObjectNode out = member(node);
        String dir = node.path("dir").asText("");
        if (out == null || !NEAR_DIRS.contains(dir)) {
            return null;
        }
        out.put("dir", dir);
        if (node.path("distance").isNumber()) {
            out.put("distance", ratio(node.path("distance").asDouble()));
        }
        return out;
    }

    /** 둘레 요소의 짧은 이름. 예: {@code 위 <h2> "요금제"}. 컴포넌트 이름이 없어 손님에게도 그대로 간다 */
    public static String nearLabel(JsonNode near) {
        String dir = switch (near.path("dir").asText("")) {
            case "up" -> "위";
            case "down" -> "아래";
            case "left" -> "왼쪽";
            case "right" -> "오른쪽";
            default -> "";
        };
        String name = viewerLabel(near);
        return cut(dir + (name == null ? "" : " " + name), MAX_VIEWER_LABEL);
    }

    private ObjectNode fullScreen() {
        return json.createObjectNode().put("x", 0.0).put("y", 0.0).put("w", 1.0).put("h", 1.0);
    }

    /**
     * 칸 하나의 짧은 이름. 종류 + 이름(id 또는 글). 예: {@code 폼 #profile}, {@code 컴포넌트 ProfileForm}, {@code 화면 전체}.
     *
     * @param withComponent 컴포넌트 이름을 넣을지. 손님 뷰어에는 바깥 칸의 컴포넌트 이름을 보내지 않는다(§15.2)
     */
    public static String scopeLabel(JsonNode scope, boolean withComponent) {
        String kind = scope.path("kind").asText("");
        String component = scope.path("component").asText("");
        String label = switch (kind) {
            case "screen" -> SCREEN_LABEL;
            case "region" -> {
                // 끈 영역: 안의 요소 수, 비었으면 빈 자리(§15.4)
                JsonNode members = scope.path("members");
                yield !members.isInt() ? "영역" : members.asInt() == 0 ? "빈 자리" : "영역 · 요소 " + members.asInt() + "개";
            }
            case "text" -> {
                String text = scope.path("text").asText("");
                yield text.isEmpty() ? "글자" : "글자 \"" + text + '"';
            }
            case "element" -> {
                String element = viewerLabel(scope);
                yield element == null ? "요소" : element;
            }
            case "component" -> withComponent && !component.isEmpty()
                    ? "컴포넌트 " + component
                    : named(groupWord(scope.path("type").asText("")), scope);
            default -> named(groupWord(scope.path("type").asText("")), scope);
        };
        if (withComponent && !component.isEmpty() && !"component".equals(kind) && !"screen".equals(kind)) {
            label = label + " · " + component;
        }
        return cut(label, MAX_VIEWER_LABEL);
    }

    /**
     * 사람이 고른 범위의 이름(§15.10). 기기 알림·목록·상세·뷰어의 내 피드백이 같이 쓴다. 범위 사다리가 없는 결과(§14, 옛 CLI)는
     * 지금처럼 요소 이름이다.
     */
    public static String selectedLabel(JsonNode description) {
        List<JsonNode> places = places(description);
        String first = placeLabel(description);
        if (places.size() < 2) {
            return first;
        }
        // §15.8: 여러 곳이면 첫 곳과 개수로("① 폼 ProfileForm 외 1곳")
        return "① " + (first == null ? "짚은 자리" : first) + " 외 " + (places.size() - 1) + "곳";
    }

    /**
     * 짚은 곳들(§15.5·§15.10). ①은 맨 위 필드이고, ②부터는 {@code marks[].probe} 에 그 곳에서 고른 칸({@code scope})을 넣은 것이다.
     * {@code marks} 가 없는 행(§14, PR-4 까지)은 한 곳이다.
     */
    public static List<JsonNode> places(JsonNode description) {
        List<JsonNode> out = new ArrayList<>();
        out.add(description);
        JsonNode marks = description.path("marks");
        if (marks.isArray()) {
            for (JsonNode mark : marks) {
                if (!mark.path("probe").isObject()) {
                    continue;
                }
                ObjectNode place = mark.path("probe").deepCopy();
                if (mark.path("scope").isInt()) {
                    place.put("scope", mark.path("scope").asInt());
                }
                out.add(place);
            }
        }
        return out;
    }

    /** 짚은 곳 하나에서 사람이 고른 범위의 이름 */
    public static String placeLabel(JsonNode description) {
        JsonNode scopes = description.path("scopes");
        if (scopes.isArray() && !scopes.isEmpty()) {
            int chosen = description.path("scope").asInt(description.path("defaultScope").asInt(0));
            JsonNode scope = scopes.path(chosen >= 0 && chosen < scopes.size() ? chosen : 0);
            if (!"element".equals(scope.path("kind").asText())) {
                return scopeLabel(scope, true);
            }
        }
        JsonNode element = description.path("element");
        return element.isObject() ? viewerLabel(element) : null;
    }

    /** 사람이 고른 칸. 사다리가 없으면 빈 값 */
    public static Optional<JsonNode> selectedScope(JsonNode description) {
        JsonNode scopes = description.path("scopes");
        if (!scopes.isArray() || scopes.isEmpty()) {
            return Optional.empty();
        }
        int chosen = description.path("scope").asInt(description.path("defaultScope").asInt(0));
        return Optional.of(scopes.path(chosen >= 0 && chosen < scopes.size() ? chosen : 0));
    }

    private static String named(String word, JsonNode scope) {
        String id = scope.path("id").asText("");
        String text = scope.path("text").asText("");
        if (!id.isEmpty()) {
            return word + " #" + id;
        }
        if (!text.isEmpty()) {
            return word + " \"" + cut(text, 20) + '"';
        }
        return word;
    }

    /**
     * 덩어리 칸의 종류 이름. 웹 태그·역할, Android 뷰 클래스, iOS 접근성 종류를 사람이 부르는 말로 옮기고, 모르는 것은 그대로
     * 둔다(§15.8).
     */
    public static String groupWord(String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "form" -> "폼";
            case "nav", "navigation", "menu", "menubar" -> "메뉴";
            case "header", "banner", "toolbar", "appbarlayout", "navigationbar" -> "머리글";
            case "footer", "contentinfo" -> "바닥글";
            case "main" -> "본문";
            case "section", "region" -> "구역";
            case "article" -> "글 묶음";
            case "aside", "complementary" -> "곁 내용";
            case "dialog", "alertdialog", "alert", "sheet" -> "대화 상자";
            case "ul", "ol", "list", "table", "tablist", "grid", "recyclerview", "listview", "gridview",
                 "collectionview", "outline" -> "목록";
            case "li", "listitem", "row", "cell" -> "항목";
            case "fieldset" -> "입력 묶음";
            case "group", "linearlayout", "framelayout", "relativelayout", "constraintlayout", "coordinatorlayout",
                 "viewgroup", "view" -> "묶음";
            case "cardview", "materialcardview" -> "카드";
            case "scrollview", "nestedscrollview", "horizontalscrollview" -> "스크롤 영역";
            case "tabbar", "bottomnavigationview" -> "탭 막대";
            case "" -> "덩어리";
            default -> "<" + type + ">";
        };
    }

    // ------------------------------------------------------------------ 부분

    private ObjectNode element(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        ObjectNode out = json.createObjectNode();
        putText(out, "type", node.path("type"), MAX_TYPE);
        putText(out, "text", node.path("text"), MAX_TEXT);
        putText(out, "id", node.path("id"), MAX_TEXT);
        putText(out, "selector", node.path("selector"), MAX_SELECTOR);
        ObjectNode bounds = ratioRect(node.path("bounds"));
        if (bounds != null) {
            out.set("bounds", bounds);
        }
        // 종류도 이름도 테두리도 없으면 요소라고 할 것이 없다
        return out.isEmpty() ? null : out;
    }

    private ObjectNode ancestor(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        ObjectNode out = json.createObjectNode();
        putText(out, "type", node.path("type"), MAX_TYPE);
        putText(out, "id", node.path("id"), MAX_TEXT);
        putText(out, "text", node.path("text"), MAX_TEXT);
        return out.isEmpty() ? null : out;
    }

    private ObjectNode component(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String name = text(node.path("name"), MAX_NAME);
        if (name == null) {
            return null;
        }
        ObjectNode out = json.createObjectNode().put("name", name);
        String file = path(node.path("file"));
        if (file != null) {
            out.put("file", file);
            int line = node.path("line").asInt(0);
            if (line > 0) {
                out.put("line", line);
            }
        }
        return out;
    }

    private ObjectNode sourceHint(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String file = path(node.path("file"));
        if (file == null) {
            return null;
        }
        ObjectNode out = json.createObjectNode().put("file", file);
        int line = node.path("line").asInt(0);
        if (line > 0) {
            out.put("line", line);
        }
        String reason = node.path("reason").asText("");
        if (HINT_REASONS.contains(reason)) {
            out.put("reason", reason);
        }
        // §15.3: 넓은 칸의 id 로 찾은 후보는 어느 칸인지 적혀 온다
        int scope = node.path("scope").isInt() ? node.path("scope").asInt() : 0;
        if (scope > 0 && scope < MAX_SCOPES) {
            out.put("scope", scope);
        }
        return out;
    }

    private ArrayNode list(JsonNode node, int max, java.util.function.Function<JsonNode, ObjectNode> each) {
        ArrayNode out = json.createArrayNode();
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (out.size() >= max) {
                break;
            }
            ObjectNode clean = each.apply(item);
            if (clean != null) {
                out.add(clean);
            }
        }
        return out;
    }

    private ObjectNode ratioPoint(JsonNode node) {
        if (!node.path("x").isNumber() || !node.path("y").isNumber()) {
            return null;
        }
        return json.createObjectNode().put("x", ratio(node.path("x").asDouble())).put("y", ratio(node.path("y").asDouble()));
    }

    private ObjectNode ratioRect(JsonNode node) {
        if (!node.path("x").isNumber() || !node.path("y").isNumber()
                || !node.path("w").isNumber() || !node.path("h").isNumber()) {
            return null;
        }
        double x = ratio(node.path("x").asDouble());
        double y = ratio(node.path("y").asDouble());
        double w = ratio(Math.min(node.path("w").asDouble(), 1 - x));
        double h = ratio(Math.min(node.path("h").asDouble(), 1 - y));
        return json.createObjectNode().put("x", x).put("y", y).put("w", w).put("h", h);
    }

    /** 뷰어에 보일 짧은 이름. 예: {@code <button> "저장"}, {@code <Button> #save_button} */
    public static String viewerLabel(JsonNode element) {
        String type = element.path("type").asText("");
        String text = element.path("text").asText("");
        String id = element.path("id").asText("");
        StringBuilder out = new StringBuilder();
        if (!type.isEmpty()) {
            out.append('<').append(type).append('>');
        }
        if (!text.isEmpty()) {
            out.append(out.isEmpty() ? "" : " ").append('"').append(text).append('"');
        } else if (!id.isEmpty()) {
            out.append(out.isEmpty() ? "" : " ").append('#').append(id);
        }
        String label = out.toString();
        return label.isEmpty() ? null : cut(label, MAX_VIEWER_LABEL);
    }

    // ------------------------------------------------------------------ 값

    private static void putText(ObjectNode out, String field, JsonNode node, int max) {
        String value = text(node, max);
        if (value != null) {
            out.put(field, value);
        }
    }

    /** 문자열만 받는다. 제어 문자를 지우고 앞뒤 공백을 걷고 자른다. 비면 null. */
    static String text(JsonNode node, int max) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().replaceAll("\\p{Cntrl}", " ").strip();
        return value.isEmpty() ? null : cut(value, max);
    }

    /** 프로젝트 안의 상대 경로만. 절대 경로·상위 경로·드라이브 문자·NUL 은 버린다. 구분자는 {@code /} 로 맞춘다. */
    static String path(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String raw = node.asText();
        if (raw.isEmpty() || raw.length() > MAX_PATH || raw.indexOf('\0') >= 0 || raw.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        String value = raw.replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("~") || value.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String segment : value.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return value;
    }

    private static double ratio(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        double clamped = Math.max(0, Math.min(1, value));
        return Math.round(clamped * 10_000) / 10_000.0;
    }

    private static String cut(String value, int max) {
        return value.codePointCount(0, value.length()) > max
                ? value.substring(0, value.offsetByCodePoints(0, max))
                : value;
    }
}
