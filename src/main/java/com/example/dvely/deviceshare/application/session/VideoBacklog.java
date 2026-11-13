package com.example.dvely.deviceshare.application.session;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 보는 사람 한 명에게 아직 못 보낸 H.264 장들(설계 §12.3). 장마다 앞 장에 기대므로 JPEG 처럼 덮어쓰지 않고 순서대로 둔다.
 *
 * <ul>
 *   <li>처음에는 키 프레임을 기다린다. 그 전의 장은 풀 수 없어 버린다.</li>
 *   <li>키 프레임이 오면 못 보낸 장을 모두 버리고 키 프레임부터 보낸다. 밀린 만큼 건너뛰는 셈이다.</li>
 *   <li>밀린 양이 상한(기본 3MiB)을 넘게 되면 비우고 다시 키 프레임을 기다린다. 공유의 GOP 상한(2MiB)보다 커서, 늦게 들어온 사람에게 GOP 를 통째로 다시 보낼 수 있다.</li>
 * </ul>
 *
 * <p>스레드 안전하지 않다. 쓰는 쪽(보는 사람 채널)이 자기 모니터로 감싼다.</p>
 */
public final class VideoBacklog {

    public static final int DEFAULT_MAX_BYTES = 3 * 1024 * 1024;

    private final int maxBytes;
    private final Deque<byte[]> chunks = new ArrayDeque<>();
    private long queuedBytes;
    private boolean waitingForKeyFrame = true;

    public VideoBacklog() {
        this(DEFAULT_MAX_BYTES);
    }

    VideoBacklog(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** @return 이 호출 뒤 키 프레임을 기다리는지(버렸거나 넘쳐 비웠다) */
    public boolean offer(byte[] chunk, boolean keyFrame) {
        if (keyFrame) {
            clear();
            waitingForKeyFrame = false;
        } else if (waitingForKeyFrame) {
            return true;
        }
        if (queuedBytes + chunk.length > maxBytes) {
            clear();
            waitingForKeyFrame = true;
            return true;
        }
        chunks.addLast(chunk);
        queuedBytes += chunk.length;
        return false;
    }

    /** @return 다음에 보낼 장, 없으면 null */
    public byte[] poll() {
        byte[] chunk = chunks.pollFirst();
        if (chunk != null) {
            queuedBytes -= chunk.length;
        }
        return chunk;
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public boolean waitingForKeyFrame() {
        return waitingForKeyFrame;
    }

    long queuedBytes() {
        return queuedBytes;
    }

    private void clear() {
        chunks.clear();
        queuedBytes = 0;
    }
}
