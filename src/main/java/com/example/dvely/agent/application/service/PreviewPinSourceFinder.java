package com.example.dvely.agent.application.service;

import com.example.dvely.agent.infrastructure.docker.ContainerPaths;
import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 프리뷰에서 짚은 곳의 소스 후보(설계 §16.7). 기기 공유 짚기에서는 CLI 가 사용자 컴퓨터에서 찾는다(§14.3) — 프리뷰 짚기는
 * 소스가 프리뷰 컨테이너의 작업 공간에 있으니, CODE 단계 직전에 서버가 그 안에서 찾는다.
 *
 * <p>찾는 말: 요소 id, 고른 칸(문장 칸이면 그 문장)과 요소의 글자, 선택자의 클래스, 개발 빌드의 컴포넌트 이름. "더 크게" 같은
 * 요청은 대개 CSS 를 고치므로 스타일 파일도 찾는다. 후보는 답이 아니라 에이전트가 먼저 열어 볼 곳이다 — 곳마다 3개, 5초.</p>
 *
 * <p><b>찾는 말은 명령에 넣지 않는다.</b> 짚기 결과는 프리뷰 문서에서 온 값이라(§16.2) 그대로 셸에 넣으면 주입이 된다.
 * exec 의 환경변수({@code T1}…)로만 넘기고 스크립트는 {@code "$T1"} 로 읽는다. 로그에도 남지 않는다.</p>
 */
@Slf4j
@Component
public class PreviewPinSourceFinder {

    static final int PER_PLACE = 3;
    private static final int PER_TERM = 4;
    private static final int MAX_TERMS = 24;
    private static final int MIN_TEXT = 2;
    private static final int MAX_TEXT = 60;

    /**
     * 찾을 파일. 프리뷰 컨테이너는 alpine 이라 grep 이 BusyBox 다 — {@code --include}·{@code --exclude-dir}·{@code -I} 가 없다
     * (Docker 통합 테스트에서 아무것도 못 찾아 알았다). 그래서 find 로 고르고 xargs 로 grep 에 넘긴다.
     */
    private static final String FILES = "find . \\( -name node_modules -o -name dist -o -name build -o -name .git -o -name .next"
            + " -o -name .nuxt -o -name .svelte-kit -o -name out -o -name coverage \\) -prune -o -type f \\("
            + " -name '*.html' -o -name '*.htm' -o -name '*.jsx' -o -name '*.tsx' -o -name '*.js' -o -name '*.mjs' -o -name '*.ts'"
            + " -o -name '*.vue' -o -name '*.svelte' -o -name '*.astro' -o -name '*.css' -o -name '*.scss' -o -name '*.sass'"
            + " -o -name '*.less' \\) ! -name '*.min.js' -size -512k -print0";

    /** 이름 같은 말(id·클래스·컴포넌트)은 단어로만 찾는다 — {@code btn} 이 {@code btn-primary} 안에서 잡히지 않게 */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][\\w-]{1,60}");
    private static final Pattern CLASS_IN_SELECTOR = Pattern.compile("\\.([A-Za-z_][\\w-]{1,60})");
    private static final Pattern HIT = Pattern.compile("^P(\\d+):([A-Z]+) (?:\\./)?([^:]{1,300}):(\\d{1,7})$");

    /** 후보의 종류 순서: id 가 가장 좁고, 클래스는 스타일 규칙 자리를, 글자는 마크업 자리를 가리킨다 */
    private static final List<String> REASON_ORDER = List.of("ID", "COMPONENT", "CLASS", "TEXT");

    private final DockerContainerService docker;
    private final ObjectMapper json = new ObjectMapper();

    public PreviewPinSourceFinder(DockerContainerService docker) {
        this.docker = docker;
    }

    /**
     * @param places 정리된 짚기 기술(①②… 순서)
     * @return 곳마다의 후보 {@code {file, line, reason}}(곳마다 3개까지). 못 찾거나 실패하면 빈 목록들
     */
    public List<List<JsonNode>> find(String containerId, List<JsonNode> places) {
        List<List<JsonNode>> out = new ArrayList<>();
        places.forEach(p -> out.add(new ArrayList<>()));
        List<Term> terms = termsOf(places);
        if (containerId == null || terms.isEmpty()) {
            return out;
        }
        StringBuilder script = new StringBuilder("cd ").append(ContainerPaths.APP_DIR).append(" 2>/dev/null || exit 0\n")
                .append("L=$(mktemp 2>/dev/null || echo /tmp/qeploy-hints-$$)\n")
                .append(FILES).append(" > \"$L\" 2>/dev/null\n")
                .append("s() { xargs -0 grep -HnF $3 -e \"$1\" < \"$L\" 2>/dev/null | head -n ").append(PER_TERM)
                .append(" | cut -d: -f1,2 | sed \"s|^|$2 |\"; }\n");
        List<String> env = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            Term t = terms.get(i);
            env.add("T" + i + "=" + t.value());
            script.append("s \"$T").append(i).append("\" P").append(t.place()).append(':').append(t.reason())
                    .append(t.word() ? " -w" : " ''").append('\n');
        }
        script.append("rm -f \"$L\"\n");
        String output;
        try {
            output = docker.execWithExitCode(containerId, "timeout 5 sh -c '" + script.toString().replace("'", "'\\''") + "'", env).output();
        } catch (RuntimeException e) {
            log.warn("[PreviewPinSourceFinder] 작업 공간에서 소스 후보를 찾지 못했다: {}", e.getMessage());
            return out;
        }
        Map<Integer, Map<String, Hit>> found = new LinkedHashMap<>();
        for (String line : output == null ? new String[0] : output.split("\n")) {
            Matcher m = HIT.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            int place = Integer.parseInt(m.group(1));
            String file = m.group(3);
            if (place < 0 || place >= places.size() || file.startsWith("/") || file.contains("..")) {
                continue;
            }
            Hit hit = new Hit(file, Integer.parseInt(m.group(4)), m.group(2));
            // 같은 줄이 여러 말로 잡히면 더 좁은 까닭 하나로
            found.computeIfAbsent(place, k -> new LinkedHashMap<>())
                    .merge(file + ":" + hit.line(), hit, (a, b) -> rank(a) <= rank(b) ? a : b);
        }
        found.forEach((place, hits) -> hits.values().stream()
                .sorted(Comparator.comparingInt(PreviewPinSourceFinder::rank)
                        .thenComparingInt(h -> extRank(h.file()))
                        .thenComparing(Hit::file).thenComparingInt(Hit::line))
                .limit(PER_PLACE)
                .forEach(h -> out.get(place).add(json.createObjectNode()
                        .put("file", h.file()).put("line", h.line()).put("reason", h.reason()))));
        return out;
    }

    /** 곳마다 찾을 말. 같은 곳에서 같은 말은 한 번 */
    List<Term> termsOf(List<JsonNode> places) {
        List<Term> terms = new ArrayList<>();
        for (int p = 0; p < places.size() && terms.size() < MAX_TERMS; p++) {
            JsonNode place = places.get(p);
            JsonNode element = place.path("element");
            Set<String> seen = new LinkedHashSet<>();
            addName(terms, seen, p, "ID", element.path("id").asText(""));
            // 고른 칸: 문장 칸이면 그 문장이 고칠 글이다. 다른 칸이면 그 칸의 id
            JsonNode chosen = chosenScope(place);
            if (chosen != null && "text".equals(chosen.path("kind").asText())) {
                addText(terms, seen, p, chosen.path("text").asText(""));
            } else if (chosen != null) {
                addName(terms, seen, p, "ID", chosen.path("id").asText(""));
            }
            for (JsonNode c : place.path("components")) {
                addName(terms, seen, p, "COMPONENT", c.path("name").asText(""));
            }
            // 선택자의 마지막 마디(요소 자신)의 클래스. 짧은 공용 클래스(btn)보다 긴 것(btn-primary)이 좁다
            String selector = element.path("selector").asText("");
            String last = selector.contains(">") ? selector.substring(selector.lastIndexOf('>') + 1) : selector;
            Matcher m = CLASS_IN_SELECTOR.matcher(last);
            List<String> classes = new ArrayList<>();
            while (m.find()) {
                classes.add(m.group(1));
            }
            classes.sort(Comparator.comparingInt(String::length).reversed());
            int added = 0;
            for (String c : classes) {
                if (c.length() >= 4 && added < 2) {
                    addName(terms, seen, p, "CLASS", c);
                    added++;
                }
            }
            addText(terms, seen, p, element.path("text").asText(""));
        }
        return terms.size() > MAX_TERMS ? List.copyOf(terms.subList(0, MAX_TERMS)) : terms;
    }

    private static JsonNode chosenScope(JsonNode place) {
        JsonNode scopes = place.path("scopes");
        if (!scopes.isArray() || scopes.isEmpty()) {
            return null;
        }
        int i = place.path("scope").asInt(place.path("defaultScope").asInt(0));
        return scopes.path(i >= 0 && i < scopes.size() ? i : 0);
    }

    private static void addName(List<Term> terms, Set<String> seen, int place, String reason, String value) {
        if (value != null && NAME.matcher(value).matches() && seen.add(value)) {
            terms.add(new Term(place, reason, value, true));
        }
    }

    /** 보이는 글: 줄바꿈·제어 문자를 지우고 60자까지. 너무 짧은 글(한 글자)은 어디에나 있다 */
    private static void addText(List<Term> terms, Set<String> seen, int place, String value) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (text.codePointCount(0, text.length()) < MIN_TEXT) {
            return;
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_TEXT));
        }
        if (seen.add(text)) {
            terms.add(new Term(place, "TEXT", text, false));
        }
    }

    private static int rank(Hit h) {
        int i = REASON_ORDER.indexOf(h.reason());
        return i < 0 ? REASON_ORDER.size() : i;
    }

    /** 마크업이 먼저, 그다음 스타일, 그다음 스크립트 */
    static int extRank(String file) {
        String f = file.toLowerCase(Locale.ROOT);
        if (f.matches(".*\\.(html?|jsx|tsx|vue|svelte|astro)$")) {
            return 0;
        }
        if (f.matches(".*\\.(css|scss|sass|less)$")) {
            return 1;
        }
        return 2;
    }

    record Term(int place, String reason, String value, boolean word) {
    }

    private record Hit(String file, int line, String reason) {
    }
}
