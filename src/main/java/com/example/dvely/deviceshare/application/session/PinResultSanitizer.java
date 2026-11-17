package com.example.dvely.deviceshare.application.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        boolean resolved = element != null || screenOnly;
        if (resolved && !scopes.isEmpty()) {
            out.set("scopes", scopes);
            int defaultScope = raw.path("defaultScope").asInt(0);
            out.put("defaultScope", defaultScope >= 0 && defaultScope < scopes.size() ? defaultScope : 0);
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
        String label = element != null ? viewerLabel(element) : screenOnly ? SCREEN_LABEL : null;
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
        out.set("bounds", bounds);
        return out;
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
            case "region" -> "영역";
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

    /** 덩어리 칸의 종류 이름. 태그·역할을 사람이 부르는 말로 옮기고, 모르는 것은 태그 그대로 둔다 */
    public static String groupWord(String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "form" -> "폼";
            case "nav", "navigation", "menu", "menubar" -> "메뉴";
            case "header", "banner" -> "머리글";
            case "footer", "contentinfo" -> "바닥글";
            case "main" -> "본문";
            case "section", "region" -> "구역";
            case "article" -> "글 묶음";
            case "aside", "complementary" -> "곁 내용";
            case "dialog", "alertdialog" -> "대화 상자";
            case "ul", "ol", "list", "table", "tablist", "grid" -> "목록";
            case "li", "listitem", "row", "cell" -> "항목";
            case "fieldset", "group" -> "입력 묶음";
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
