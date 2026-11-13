package com.example.dvely.deviceshare.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 보는 사람 한 명의 H.264 순서 큐(설계 §12.3). 상한은 작게 줄여 본다. */
class VideoBacklogTest {

    private final VideoBacklog backlog = new VideoBacklog(100);

    @Test
    void 새_큐는_키_프레임을_기다리고_그_전의_장은_버린다() {
        assertThat(backlog.waitingForKeyFrame()).isTrue();
        assertThat(backlog.offer(chunk(1, 10), false)).isTrue();
        assertThat(backlog.isEmpty()).isTrue();

        assertThat(backlog.offer(chunk(2, 10), true)).isFalse();
        assertThat(backlog.waitingForKeyFrame()).isFalse();
        assertThat(drain()).containsExactly(2);
    }

    @Test
    void 키_프레임_뒤의_장은_들어온_순서대로_나간다() {
        backlog.offer(chunk(1, 10), true);
        backlog.offer(chunk(2, 10), false);
        backlog.offer(chunk(3, 10), false);
        assertThat(backlog.queuedBytes()).isEqualTo(30);
        assertThat(drain()).containsExactly(1, 2, 3);
        assertThat(backlog.queuedBytes()).isZero();
        assertThat(backlog.poll()).isNull();
    }

    @Test
    void 새_키_프레임은_못_보낸_장을_버리고_맨_앞에_선다() {
        backlog.offer(chunk(1, 10), true);
        backlog.offer(chunk(2, 10), false);
        assertThat(backlog.poll()[0]).isEqualTo((byte) 1); // 1 은 이미 보냈다
        backlog.offer(chunk(3, 10), false);

        assertThat(backlog.offer(chunk(4, 10), true)).isFalse();
        backlog.offer(chunk(5, 10), false);
        assertThat(drain()).containsExactly(4, 5);
    }

    @Test
    void 상한을_넘게_되면_비우고_다음_키_프레임까지_기다린다() {
        backlog.offer(chunk(1, 60), true);
        assertThat(backlog.offer(chunk(2, 40), false)).isFalse(); // 꼭 100 은 들어간다
        assertThat(backlog.offer(chunk(3, 1), false)).isTrue();
        assertThat(backlog.isEmpty()).isTrue();
        assertThat(backlog.queuedBytes()).isZero();

        assertThat(backlog.offer(chunk(4, 1), false)).isTrue(); // 기다리는 동안 키 프레임이 아닌 장은 버린다
        assertThat(backlog.isEmpty()).isTrue();

        assertThat(backlog.offer(chunk(5, 10), true)).isFalse();
        assertThat(drain()).containsExactly(5);
    }

    @Test
    void 보낸_만큼은_상한에서_빠진다() {
        backlog.offer(chunk(1, 60), true);
        backlog.poll();
        assertThat(backlog.offer(chunk(2, 60), false)).isFalse();
        assertThat(drain()).containsExactly(2);
    }

    @Test
    void 상한보다_큰_키_프레임은_담지_않고_계속_기다린다() {
        assertThat(backlog.offer(chunk(1, 101), true)).isTrue();
        assertThat(backlog.isEmpty()).isTrue();
        assertThat(backlog.waitingForKeyFrame()).isTrue();
    }

    @Test
    void 기본_상한은_3MiB_로_GOP_상한보다_크다() {
        VideoBacklog defaults = new VideoBacklog();
        assertThat(defaults.offer(new byte[VideoBacklog.DEFAULT_MAX_BYTES], true)).isFalse();
        assertThat(defaults.offer(new byte[1], false)).isTrue();
        assertThat(VideoBacklog.DEFAULT_MAX_BYTES).isGreaterThan(ShareSession.MAX_GOP_BYTES);
    }

    /** 첫 바이트로 어느 장인지 알아본다. */
    private static byte[] chunk(int id, int size) {
        byte[] chunk = new byte[size];
        chunk[0] = (byte) id;
        return chunk;
    }

    private List<Integer> drain() {
        List<Integer> ids = new ArrayList<>();
        for (byte[] chunk = backlog.poll(); chunk != null; chunk = backlog.poll()) {
            ids.add((int) chunk[0]);
        }
        return ids;
    }
}
