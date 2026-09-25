package com.example.dvely.template.infrastructure.catalog;

import com.example.dvely.template.domain.model.Template;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 마지막 성공 카탈로그를 DB 에 남기고 되살린다 (#395).
 *
 * <p>있어도 되고 없어도 되는 보조 장치다. <b>어느 실패도 카탈로그 서빙을 막지 않는다</b> —
 * 저장이 실패하면 그냥 다음 갱신에 다시 쓰고, 복원이 실패하면 이 기능이 없던 때와 똑같이
 * 503 으로 떨어진다. 그래서 모든 경로가 예외를 밖으로 내보내지 않는다.</p>
 *
 * <p>원문 JSON 이 아니라 <b>파싱 결과</b>를 담는다. 원문을 담으면 복원 시점의 역직렬화 규칙이
 * 수신 시점과 갈릴 수 있다. 우리가 쓴 것을 우리가 읽는 형태로 맞춘다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateCatalogSnapshotStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Template>> TEMPLATE_LIST = new TypeReference<>() {
    };

    private final TemplateCatalogSnapshotRepository repository;

    /**
     * 성공한 갱신을 남긴다. best-effort — 실패는 WARN 으로만 남는다.
     *
     * <p>조용히 넘기지 않고 WARN 을 남기는 이유: 저장이 계속 실패하고 있으면 재시작 내구성이
     * 사실은 없는 상태인데, 그 사실은 <b>정작 필요해지는 순간에야</b> 드러난다.</p>
     *
     * <h2>여기에 {@code @Transactional} 을 붙이면 안 된다</h2>
     * 붙이면 커밋을 <b>이 메서드 밖에서</b> 프록시가 한다. 그러면 플러시·커밋 시점의 실패가 아래
     * try/catch 를 그냥 지나쳐 호출자에게 올라간다. 호출자는 그것을 "카탈로그 갱신 실패"로 읽고,
     * cold start 라면 <b>네트워크가 성공했는데도 503</b> 을 낸다 — 보조 장치가 본 기능을 죽인다.
     *
     * <p>그래서 트랜잭션을 걸지 않고 {@code repository.save} 하나로 끝낸다. 그 안쪽 트랜잭션은
     * 호출이 돌아오기 전에 커밋되므로 실패가 이 catch 에 잡힌다. 더티체킹으로 갱신하지 않는
     * 이유도 같다 — 트랜잭션이 없으면 조회한 엔티티는 detached 라서 변경이 반영되지 않는다.
     * ID 가 고정값이므로 {@code save} 는 merge 로 가고, 행이 있으면 UPDATE 없으면 INSERT 다.</p>
     */
    public void save(List<Template> templates, Instant fetchedAt, String sourceUrl) {
        try {
            String payload = MAPPER.writeValueAsString(templates);
            repository.save(TemplateCatalogSnapshotEntity.of(payload, fetchedAt, sourceUrl));
        } catch (Exception e) {
            log.warn("[Template] 카탈로그 스냅샷 저장 실패 — 재시작 시 복원할 것이 없습니다: {}", e.toString());
        }
    }

    /**
     * 재시작 후 네트워크가 실패했을 때만 부른다.
     *
     * <p>세 가지를 확인하고 하나라도 어긋나면 비워서 돌려준다 — 호출자는 그때 오늘과 같은
     * 503 을 낸다.</p>
     * <ol>
     *   <li><b>같은 카탈로그인가</b> — 설정 URL 이 바뀌었다면 남의 카탈로그다</li>
     *   <li><b>상한 안인가</b> — 너무 낡은 목록은 없어진 템플릿을 고르게 만든다. 그러면
     *       {@code TemplateCatalogGuard} 가 막으려던 "고를 때는 200, 만들 때는 실패"가 된다</li>
     *   <li><b>지금 기준으로도 안전한 값인가</b> — 스킴 검사가 없던 때 저장된 스냅샷이 남아
     *       있을 수 있다. 복원은 수신과 같은 검사를 통과해야 한다</li>
     * </ol>
     */
    public Optional<Restored> restore(String sourceUrl, Duration maxAge) {
        try {
            TemplateCatalogSnapshotEntity entity =
                    repository.findById(TemplateCatalogSnapshotEntity.SINGLETON_ID).orElse(null);
            if (entity == null) {
                return Optional.empty();
            }
            if (!sourceUrl.equals(entity.getSourceUrl())) {
                log.warn("[Template] 스냅샷의 출처가 지금 설정과 다릅니다 — 복원하지 않습니다: 스냅샷={} 설정={}",
                        entity.getSourceUrl(), sourceUrl);
                return Optional.empty();
            }
            Duration age = Duration.between(entity.getFetchedAt(), Instant.now());
            if (age.compareTo(maxAge) > 0) {
                log.warn("[Template] 스냅샷이 상한보다 낡았습니다 — 복원하지 않습니다: 나이={} 상한={}", age, maxAge);
                return Optional.empty();
            }

            List<Template> templates = MAPPER.readValue(entity.getPayload(), TEMPLATE_LIST);
            if (templates.isEmpty()) {
                log.warn("[Template] 스냅샷이 비어 있습니다 — 복원하지 않습니다");
                return Optional.empty();
            }
            TemplateCatalogUrlPolicy.assertBrowserSafe(templates);

            log.warn("[Template] 네트워크 갱신에 실패해 DB 스냅샷으로 시작합니다: {}종 나이={} ({})",
                    templates.size(), age, sourceUrl);
            return Optional.of(new Restored(templates, age));
        } catch (Exception e) {
            // ERROR 다 — 복원 경로가 깨져 있으면 재시작 내구성이 없는 것이고, 그 사실은
            // 정작 필요한 순간에만 드러난다. WARN 에 묻히면 아무도 보지 않는다.
            log.error("[Template] 카탈로그 스냅샷 복원 실패 — 스냅샷이 없는 것과 같이 동작합니다", e);
            return Optional.empty();
        }
    }

    /** @param age 스냅샷을 네트워크에서 받은 뒤 지난 시간. 로그·관측용이다. */
    public record Restored(List<Template> templates, Duration age) {
    }
}
