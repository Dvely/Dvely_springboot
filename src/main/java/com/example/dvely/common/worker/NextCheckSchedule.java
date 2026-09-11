package com.example.dvely.common.worker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * "이 대상은 다음에 언제 다시 볼 것인가"를 들고 있는 인메모리 장부(#340 5-4 · 5-5).
 *
 * <p><b>왜 필요한가.</b> 폴러가 고정 주기로 돌면서 후보 전부를 매 주기 건드리면, 결과가 느리게
 * 바뀌는 대상(GitHub Actions 실행 · DNS 전파)에 대해 같은 외부 API 호출을 수백 번 반복한다.
 * 멈춘 배포 한 건이 포기 시각(120분)까지 매분 GitHub 을 치면 120회, 커스텀 도메인 한 건이
 * TTL(1440분)까지 매분 Cloudflare·GitHub·HTTPS 프로브를 돌면 1,440회다. 폴러의 주기는 그대로
 * 두고, <b>대상별로</b> 다음에 볼 시각을 미루는 것이 이 장부의 일이다.</p>
 *
 * <p><b>DB 가 아니라 메모리인 이유.</b> 이 간격은 정확성이 아니라 예의의 문제다 — 재시작하면
 * 장부가 비고 모든 대상을 한 번씩 다시 보게 되는데, 그것이 정확히 옳은 동작이다(재시작 직후에는
 * 상태가 바뀌었을 가능성이 오히려 높다). 인스턴스가 여럿이면 각자 자기 장부를 갖고, 호출 횟수는
 * 인스턴스 수만큼 곱해진다 — 그래도 대상당 수백 회가 수 회로 줄어드는 효과는 그대로다.</p>
 *
 * <p><b>누수.</b> 대상은 언젠가 사라진다(배포가 닫히고 도메인이 연결된다). 결론이 난 대상은
 * 호출자가 {@link #clear} 로 지우지만, 호출자가 다시 보지 못하게 된 대상(행 삭제 등)은 지울 기회
 * 자체가 없다. 그래서 오래 손대지 않은 항목은 스스로 만료시킨다.</p>
 */
public class NextCheckSchedule<K> {

    private static final long ENTRY_TTL_MS = TimeUnit.HOURS.toMillis(1);

    private final Map<K, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public NextCheckSchedule() {
        this(System::nanoTime);
    }

    /** 시계를 주입하는 생성자 — 간격은 시간에 의존하므로 테스트가 시계를 쥘 수 있어야 한다. */
    public NextCheckSchedule(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /** 지금 이 대상을 볼 차례인가. 장부에 없으면(처음 보는 대상) 언제나 그렇다. */
    public boolean due(K key) {
        Entry entry = entries.get(key);
        return entry == null || nanoTime.getAsLong() - entry.nextCheckAtNanos >= 0;
    }

    /** 이 대상을 몇 번이나 결론 없이 봤는가 — 호출자가 간격 단계를 고르는 데 쓴다. */
    public int inconclusiveChecks(K key) {
        Entry entry = entries.get(key);
        return entry == null ? 0 : entry.inconclusiveChecks;
    }

    /** 이 대상을 {@code delayMillis} 뒤에 다시 본다. */
    public void scheduleAfter(K key, long delayMillis) {
        long now = nanoTime.getAsLong();
        entries.compute(key, (ignored, previous) -> new Entry(
                now + TimeUnit.MILLISECONDS.toNanos(delayMillis),
                previous == null ? 1 : previous.inconclusiveChecks + 1,
                now
        ));
        evictStale(now);
    }

    /** 결론이 났다 — 장부에서 지운다. 같은 대상이 다시 나타나면 처음부터 센다. */
    public void clear(K key) {
        entries.remove(key);
    }

    private void evictStale(long now) {
        long ttlNanos = TimeUnit.MILLISECONDS.toNanos(ENTRY_TTL_MS);
        entries.values().removeIf(entry -> now - entry.touchedAtNanos > ttlNanos);
    }

    private record Entry(long nextCheckAtNanos, int inconclusiveChecks, long touchedAtNanos) {
    }
}
