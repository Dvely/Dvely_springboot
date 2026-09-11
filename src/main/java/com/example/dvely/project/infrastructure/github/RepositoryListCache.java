package com.example.dvely.project.infrastructure.github;

import com.example.dvely.project.application.port.out.GithubRepositoryPort.GithubRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 저장소 목록의 아주 짧은 캐시.
 *
 * <p>목록 한 번이 GitHub 왕복을 최대 10 번 한다(페이지당 100 개, 순차). 저장소 선택 화면은 뒤로
 * 갔다 돌아오는 것만으로 다시 부르므로, 같은 사용자가 몇 초 사이에 같은 목록을 열 번씩 받아온다.</p>
 *
 * <p><b>키는 반드시 사용자다.</b> 이 목록은 그 사용자의 GitHub App 설치에 딸린 저장소이므로,
 * 키가 사용자 단위가 아니면 남의 비공개 저장소 이름이 그대로 보인다. 권한 사고이지 성능 문제가
 * 아니다 — {@code RepositoryListCacheTest} 와 {@code GithubProjectClientRepositoryListTest} 가
 * 이 성질을 못박는다.</p>
 *
 * <p>수명을 60 초로 짧게 두는 이유는 방금 GitHub 에서 저장소를 만들고 넘어온 사용자다. 그래도
 * 그 1 분을 기다리게 할 수는 없으므로 호출부가 새로고침(무효화) 경로를 함께 연다.</p>
 */
class RepositoryListCache {

    static final Duration TTL = Duration.ofSeconds(60);

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    Optional<List<GithubRepository>> find(Long ownerUserId, Instant now) {
        Entry entry = entries.get(ownerUserId);
        if (entry == null || !entry.freshAt(now)) {
            return Optional.empty();
        }
        return Optional.of(entry.repositories);
    }

    void put(Long ownerUserId, List<GithubRepository> repositories, Instant now) {
        // 넘겨받은 리스트를 그대로 들고 있으면 호출부가 나중에 그것을 고칠 때 캐시된 내용까지 바뀐다.
        entries.put(ownerUserId, new Entry(List.copyOf(repositories), now.plus(TTL)));
        purgeExpired(now);
    }

    void invalidate(Long ownerUserId) {
        entries.remove(ownerUserId);
    }

    /** 60 초 지난 항목은 다시 쓰이지 않는다 — 사용자가 늘기만 해도 맵이 계속 자라지 않게 걷어낸다. */
    private void purgeExpired(Instant now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().freshAt(now)) {
                iterator.remove();
            }
        }
    }

    private record Entry(List<GithubRepository> repositories, Instant expiresAt) {

        private boolean freshAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
