package com.example.dvely.template.infrastructure.catalog;

import com.example.dvely.template.domain.model.Template;
import java.util.List;
import java.util.Locale;

/**
 * 카탈로그가 실어 온 URL 이 브라우저에 넘길 만한 것인지 본다 (#395).
 *
 * <p>카탈로그는 우리 저장소가 발행하지만, 서버는 그 값을 검사 없이 통과시키고 있었다. FE 는
 * {@code demoUrl} 을 iframe {@code src} 에, {@code thumbnailUrl} 을 {@code img src} 에 넣는다.
 * 여기에 {@code javascript:} 가 들어오면 그 값을 쓰는 <b>모든</b> 소비자가 같은 문제를 겪는다 —
 * 지금의 FE 뿐 아니라 앞으로 붙는 소비자까지.</p>
 *
 * <p>FE 도 자기 쪽에서 막았지만(Dvely_FE#117), 이 서버가 유일한 병목이므로 여기서 막는 편이
 * 소비자마다 같은 검사를 반복하는 것보다 낫다. #391 에서 배운 것과 같다 — 방어가 한 곳에만
 * 있으면 그 곳을 거치지 않는 경로가 생긴다.</p>
 *
 * <p>{@code sourceUrl} 은 여기서 보지 않는다. 그 값은 셸 {@code curl} 에 들어가므로 훨씬 좁은
 * 검사가 이미 소비 지점에 걸려 있다({@code TemplateSeedingService} 의
 * {@code ^https://[A-Za-z0-9._~:/-]+\.tar\.gz$}). 싱크에 붙은 검사를 여기로 옮기거나 느슨하게
 * 복제하지 않는다.</p>
 *
 * <h2>http 를 허용하는 이유</h2>
 * 코드 실행 벡터는 {@code javascript:}·{@code data:} 이고 {@code http} 는 아니다. https 페이지의
 * iframe 이 http 를 물면 그것은 브라우저가 mixed content 로 막는 일이다. 여기서 https 를 강제하면
 * 보안은 나아지지 않고 로컬 템플릿 서버만 깨진다.
 */
final class TemplateCatalogUrlPolicy {

    private TemplateCatalogUrlPolicy() {
    }

    /**
     * 문서 전체를 받아들일 수 있는지 본다.
     *
     * <p><b>나쁜 템플릿 하나를 빼지 않고 문서를 거부한다.</b> 조용히 빼면 카탈로그를 고친 사람은
     * 자기가 무엇을 잘못했는지 모른 채 그 템플릿이 목록에서 사라진 것만 본다. 거부하면 호출자의
     * stale-while-error 가 직전 목록을 유지하면서 WARN 을 남긴다 — 목록은 계속 나가고 문제는
     * 보인다.</p>
     *
     * @throws IllegalStateException 어긴 템플릿 ID 와 필드명을 담아 던진다
     */
    static void assertBrowserSafe(List<Template> templates) {
        for (Template template : templates) {
            // demoUrl 은 필수다 — 없으면 갤러리가 띄울 것이 없다.
            requireHttpUrl(template.id(), "demoUrl", template.demoUrl(), true);
            // thumbnailUrl 은 nullable 이다(#318). 썸네일을 내보내기 전에 발행된 카탈로그에는
            // 이 필드가 없고, 그때 템플릿 API 전체가 깨지면 안 된다. 있을 때만 본다.
            requireHttpUrl(template.id(), "thumbnailUrl", template.thumbnailUrl(), false);
        }
    }

    private static void requireHttpUrl(String templateId, String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalStateException(
                        "카탈로그 템플릿에 " + field + " 가 없습니다: templateId=" + templateId);
            }
            return;
        }
        String scheme = value.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            // 값 전체를 로그에 싣지 않는다. 어긴 것이 무엇인지 알려면 스킴까지로 충분하고,
            // 통째로 실으면 긴 data: URI 가 로그를 덮는다.
            throw new IllegalStateException("카탈로그 " + field + " 의 스킴을 신뢰할 수 없습니다: templateId="
                    + templateId + " scheme=" + schemeOf(value));
        }
    }

    /** 로그용. 콜론 앞까지만 잘라낸다. 콜론이 없으면 앞 16자만. */
    private static String schemeOf(String value) {
        int colon = value.indexOf(':');
        if (colon > 0) {
            return value.substring(0, colon);
        }
        return value.length() <= 16 ? value : value.substring(0, 16) + "...";
    }
}
