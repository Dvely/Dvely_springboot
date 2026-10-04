package com.example.dvely.auth.infrastructure.external.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.infrastructure.config.GithubProperties;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * {@code #414} 의 1번 항목("현황 확보")을 <b>대시보드 없이</b> 채운다.
 *
 * <p>이슈와 {@code docs/github-app-permissions.md} 는 "지금 App 이 실제로 요구하는 권한은
 * 대시보드에서 확인해야 한다"고 적었다. <b>그 전제가 틀렸다</b> — GitHub API 가 돌려준다:
 *
 * <ul>
 *   <li>{@code GET /app} — App 이 <b>지금 요구하는</b> 권한·이벤트
 *   <li>{@code GET /app/installations} — 각 설치가 <b>실제로 허용한</b> 권한·이벤트
 * </ul>
 *
 * <p><b>이 둘이 다를 수 있는 것이 핵심이다.</b> App 권한을 넓히면 기존 설치는 재승인할 때까지
 * 옛 범위를 유지한다. 그래서 "요구"만 보면 실제 범위를 모르고, "허용"만 보면 다음 재승인에서
 * 무엇이 늘어나는지 모른다. 이슈 4번(축소 시 재승인 영향)의 비용이 이 차이에서 나온다.</p>
 *
 * <h2>왜 기본으로 끄는가</h2>
 * 실제 GitHub App 비공개 키가 필요하고 외부 API 를 호출한다. CI 에는 키가 없고, 있어도 테스트가
 * 외부 상태에 의존하면 안 된다. {@code -Dgithubapp.it=true} 로만 켠다 —
 * {@code docker.it} 와 같은 방식이다({@code AGENTS.md}).
 *
 * <p>읽기 전용이다. {@code GET} 두 번이고 App 설정을 바꾸지 않는다.</p>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "githubapp.it", matches = "true")
class GithubAppPermissionProbeIntegrationTest {

    @Autowired
    private GithubAppClient appClient;

    @Autowired
    private GithubProperties properties;

    @Autowired
    private Environment environment;

    @Test
    void App_이_요구하는_권한과_설치가_허용한_권한을_받아_적는다() throws Exception {
        assertThat(properties.app().privateKey())
                .as("App 비공개 키가 설정되지 않았다 — 이 프로파일로는 현황을 받을 수 없다")
                .isNotBlank();

        String jwt = appJwt();
        RestClient github = RestClient.create();

        @SuppressWarnings("unchecked")
        Map<String, Object> app = github.get()
                .uri("https://api.github.com/app")
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> installations = github.get()
                .uri("https://api.github.com/app/installations?per_page=100")
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(List.class);

        assertThat(app).as("GET /app 이 비어 있다").isNotNull();

        // 무엇을 봤는지를 가장 먼저, 가장 크게 찍는다. 이 테스트를 처음 돌렸을 때 나는 출력만 보고
        // "현재 요구 수준" 이라고 문서에 적었는데, 그것은 로컬 프로파일의 App(= 테스트용 등록)이었다.
        // App 등록은 환경마다 다르다 — 측정값에 대상이 붙어 있지 않으면 엉뚱한 App 의 수치가 된다.
        System.out.println("=== 측정 대상 ===");
        System.out.println("활성 프로파일=" + String.join(",", environment.getActiveProfiles()));
        System.out.println("app slug=" + app.get("slug") + " app id=" + app.get("id")
                + "  ← 이 등록의 수치다. 다른 환경 수치가 필요하면 그 환경 설정으로 다시 돌린다");
        System.out.println("=== GET /app — App 이 지금 요구하는 것 ===");
        System.out.println("permissions=" + sorted(app.get("permissions")));
        System.out.println("events=" + app.get("events"));

        System.out.println("=== GET /app/installations — 설치가 실제로 허용한 것 ===");
        System.out.println("설치 수=" + (installations == null ? 0 : installations.size()));
        if (installations != null) {
            for (Map<String, Object> installation : installations) {
                // 계정 이름은 사용자 식별자라 출력하지 않는다 — 비교에 필요한 것은 범위뿐이다.
                System.out.println("installationId=" + installation.get("id")
                        + " repository_selection=" + installation.get("repository_selection")
                        + " suspended=" + (installation.get("suspended_at") != null));
                System.out.println("  permissions=" + sorted(installation.get("permissions")));
                System.out.println("  events=" + installation.get("events"));
            }
        }
    }

    /** 비교를 눈으로 하려면 키 순서가 안정적이어야 한다. */
    private String sorted(Object permissions) {
        if (!(permissions instanceof Map<?, ?> map)) {
            return String.valueOf(permissions);
        }
        return map.entrySet().stream()
                .sorted((a, b) -> String.valueOf(a.getKey()).compareTo(String.valueOf(b.getKey())))
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("(없음)");
    }

    /**
     * 운영 코드의 JWT 생성을 그대로 쓴다 — 테스트가 키 파싱을 따로 구현하면 그쪽이 맞는지를
     * 또 검증해야 하고, 비공개 키를 테스트 코드에서 다루게 된다.
     */
    private String appJwt() throws Exception {
        Method method = GithubAppClient.class.getDeclaredMethod("generateAppJwt");
        method.setAccessible(true);
        return (String) method.invoke(appClient);
    }
}
