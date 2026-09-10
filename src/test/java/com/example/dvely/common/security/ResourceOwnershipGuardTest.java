package com.example.dvely.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 호출자가 준 ID 로 리소스를 꺼내면서 <b>소유자를 확인하지 않는</b> 곳을 잡는다.
 *
 * <p>사용자 ID 를 아래로 넘기는 것과 그걸로 거르는 것은 다르다. 컨트롤러가
 * {@code @AuthenticationPrincipal} 을 받아 넘기고 있어도, 마지막에 {@code findById(historyId)} 로
 * 꺼내고 끝나면 남의 리소스가 그대로 나온다. 그 어긋남은 코드를 읽어서는 잘 안 보인다 — 두 값이
 * 같은 메서드 안에 나란히 있고, 한쪽만 쓰이기 때문이다.</p>
 *
 * <p>지금 코드에는 그 구멍이 없다(2026-09-11 전수 확인). 이 테스트가 지키는 것은 현재 상태가 아니라
 * <b>다음에 추가될 엔드포인트</b>다. PAT 가 생기면서 자격증명이 사람 손을 떠나 헤드리스 클라이언트의
 * 환경변수에 놓였고, 탈취된 토큰이 닿는 범위를 정하는 것이 정확히 이 확인이다.</p>
 *
 * <p>소스를 읽는 방식을 쓴다. 런타임 확인은 엔드포인트마다 시나리오를 써야 하는데, 새 엔드포인트는
 * 시나리오도 함께 빠뜨려진다 — 빠뜨린 것을 잡자는 테스트가 빠뜨림에 취약하면 의미가 없다.</p>
 */
class ResourceOwnershipGuardTest {

    private static final Path APPLICATION_SOURCES =
            Path.of("src/main/java/com/example/dvely");

    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:public|protected)\\s+[\\w<>,\\[\\]?. ]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{",
            Pattern.MULTILINE);

    /** 호출자를 나타내는 인자 이름. 컨트롤러가 {@code @AuthenticationPrincipal} 로 받아 넘기는 값이다. */
    private static final Pattern CALLER_PARAM = Pattern.compile("\\b(ownerUserId|userId)\\b");

    /** 다른 ID 를 함께 받아야 "누구 것인가" 라는 질문이 생긴다. */
    private static final Pattern OTHER_ID_PARAM = Pattern.compile("\\bLong\\s+(?!ownerUserId|userId)\\w*[Ii]d\\b");

    private static final Pattern PRIVATE_METHOD = Pattern.compile(
            "^\\s*private\\s+[\\w<>,\\[\\]?. ]+\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

    private static final Pattern BARE_FIND_BY_ID = Pattern.compile("(\\w*[Rr]epository)\\.findById\\(");

    /** 호출자를 질의로 가져갔거나, 꺼낸 행과 대조했다는 신호. */
    private static final Pattern SCOPED = Pattern.compile(
            "findOwned|OwnerUserId|AndUserId\\b|requireOwner|verifyOwner|assertOwner"
                    + "|getOwnerUserId\\(\\)|getUserId\\(\\)|isOwnedBy|belongsTo");

    /**
     * {@code userRepository.findById(userId)} 는 호출자 자신의 행이라 정의상 스코프돼 있다.
     * 다른 저장소가 여기 들어오면 그건 예외가 아니라 결함이다.
     */
    private static final Set<String> SELF_SCOPED_REPOSITORIES = Set.of("userRepository");

    @Test
    void everyMethodThatLoadsByIdAlsoScopesItToTheCaller() throws IOException {
        List<String> unscoped = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(APPLICATION_SOURCES)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/application/"))
                    .toList()) {

                String source = Files.readString(path);
                Matcher method = METHOD.matcher(source);

                while (method.find()) {
                    String params = method.group(2);
                    if (!CALLER_PARAM.matcher(params).find() || !OTHER_ID_PARAM.matcher(params).find()) {
                        continue;
                    }

                    String body = bodyAfter(source, source.indexOf('{', method.end() - 1));
                    Set<String> repositories = bareFinders(body);
                    if (repositories.isEmpty() || isScoped(body, source)) {
                        continue;
                    }
                    repositories.removeAll(SELF_SCOPED_REPOSITORIES);
                    if (repositories.isEmpty()) {
                        continue;
                    }

                    unscoped.add("%s::%s — findById(%s) 뒤에 소유자 확인이 없다"
                            .formatted(APPLICATION_SOURCES.relativize(path), method.group(1),
                                    String.join(", ", repositories)));
                }
            }
        }

        assertThat(unscoped)
                .as("""
                        호출자가 준 ID 로 리소스를 꺼내면서 소유자를 확인하지 않는 곳이다. \
                        저장소 질의에 소유자를 함께 넣거나(findByIdAndOwnerUserId...), 꺼낸 행의 \
                        소유자를 호출자와 대조해야 한다. 사용자 ID 를 인자로 받기만 하는 것은 \
                        확인이 아니다.""")
                .isEmpty();
    }

    /**
     * 이 테스트가 정말 무언가를 보고 있는지 확인한다. 스캔이 조용히 0건을 훑게 되면(경로가 바뀌거나
     * 정규식이 안 맞으면) 위 테스트는 영원히 통과하면서 아무것도 지키지 않는다.
     */
    @Test
    void theScanActuallyReachesTheCodeItClaimsToCheck() throws IOException {
        int scanned = 0;

        try (Stream<Path> paths = Files.walk(APPLICATION_SOURCES)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/application/"))
                    .toList()) {
                String source = Files.readString(path);
                Matcher method = METHOD.matcher(source);
                while (method.find()) {
                    String params = method.group(2);
                    if (CALLER_PARAM.matcher(params).find() && OTHER_ID_PARAM.matcher(params).find()) {
                        scanned++;
                    }
                }
            }
        }

        assertThat(scanned)
                .as("소유자 ID 와 리소스 ID 를 함께 받는 메서드를 하나도 못 찾았다면 스캔이 헛돌고 있다")
                .isGreaterThan(20);
    }

    /**
     * 확인이 헬퍼로 빠져 있는 경우까지 본다.
     *
     * <p>실제 코드가 그렇게 생겼다 — {@code retryDeployment} 는 {@code resolveProject(ownerUserId, ...)}
     * 를 부르고, 그 안에서 {@code findByIdAndOwnerUserIdAndDeletedFalse} 로 거른다. 메서드 본문만
     * 보면 확인이 없는 것처럼 보이지만 실제로는 있다. 이름으로 헬퍼를 허용목록에 넣는 방식은
     * 쓰지 않는다 — 확인하지 않는 {@code resolveProject} 를 누군가 쓰면 그대로 통과하기 때문이다.
     * 헬퍼의 <b>본문</b>을 본다.</p>
     *
     * <p>한 단계만 따라간다. 더 깊이 가려면 호출 그래프가 필요하고, 두 단계 아래에 숨은 확인은
     * 사람이 읽어도 못 찾는다 — 그건 테스트를 고칠 게 아니라 코드를 펴야 할 신호다.</p>
     */
    private static boolean isScoped(String body, String source) {
        if (SCOPED.matcher(body).find()) {
            return true;
        }
        for (String helper : PRIVATE_METHOD.matcher(source).results()
                .map(r -> r.group(1)).distinct().toList()) {
            if (!body.contains(helper + "(")) {
                continue;
            }
            Matcher declaration = Pattern
                    .compile("private\\s+[\\w<>,\\[\\]?. ]+\\s+" + helper + "\\s*\\([^)]*\\)\\s*\\{")
                    .matcher(source);
            if (declaration.find()
                    && SCOPED.matcher(bodyAfter(source, source.indexOf('{', declaration.end() - 1))).find()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> bareFinders(String body) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = BARE_FIND_BY_ID.matcher(body);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static String bodyAfter(String source, int openingBrace) {
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openingBrace, i + 1);
                }
            }
        }
        return source.substring(openingBrace);
    }
}
