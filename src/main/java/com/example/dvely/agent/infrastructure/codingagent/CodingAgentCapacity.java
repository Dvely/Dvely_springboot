package com.example.dvely.agent.infrastructure.codingagent;

import com.example.dvely.common.limit.GlobalLimitProperties;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.response.ErrorCode;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 서버 전체에서 동시에 도는 코딩 에이전트 실행 수의 상한(#624). 실행마다 메모리 상한 2 GiB 짜리 컨테이너가 하나 뜨고, 그 작업의
 * 프리뷰 컨테이너와 함께 돈다. 지금까지는 에이전트 실행 스레드 수(최대 8)만이 이것을 묶었다.
 *
 * <p><b>자리가 없으면 기다리지 않고 바로 거절한다.</b> 한 번의 실행이 수 분씩 걸려, 기다리게 하면 실행 스레드만 붙든 채 결국
 * 비슷하게 늦어진다. 자리는 작업 공간을 복사하기 전에 잡으므로 거절된 실행은 아무것도 바꾸지 않았고, 다시 시도하면 된다.</p>
 *
 * <p>세는 단위는 이 서버 프로세스다. 프리뷰 상한처럼 DB 로 세지 않는 이유: 코딩 에이전트 실행은 DB 에 따로 행이 없고, 백엔드는
 * Docker 데몬과 같은 VM 의 프로세스 하나로 돈다(게이트웨이가 컨테이너 IP 로 붙는 구조라 그 전제가 이미 있다).</p>
 */
@Slf4j
@Component
public class CodingAgentCapacity {

    private final int max;
    private final Semaphore slots;

    public CodingAgentCapacity(GlobalLimitProperties limits) {
        this.max = limits.maxCodingAgentContainers();
        this.slots = new Semaphore(max);
    }

    /**
     * 자리를 하나 잡는다. 돌려받은 것을 닫으면 자리가 돌아간다(여러 번 닫아도 한 번만 돌아간다).
     *
     * @throws UserLimitExceededException 자리가 없을 때({@code CODING_AGENT_CAPACITY_EXCEEDED})
     */
    public Slot acquire(Long userId) {
        if (!slots.tryAcquire()) {
            log.warn("[CodingAgentCapacity] 서버 전체 코딩 에이전트 상한: inUse={} max={} userId={}", inUse(), max, userId);
            // 서버 전체 수는 다른 사용자의 활동이라 알려 주지 않는다.
            throw new UserLimitExceededException(
                    ErrorCode.CODING_AGENT_CAPACITY_EXCEEDED,
                    "지금은 서버의 코딩 에이전트가 모두 사용 중이라 실행하지 못했습니다. 변경은 반영하지 않았습니다. "
                            + "잠시 뒤 다시 시도해주세요.");
        }
        return new Slot();
    }

    /** 지금 잡혀 있는 자리 수. */
    public int inUse() {
        return max - slots.availablePermits();
    }

    /** 잡은 자리 하나. try-with-resources 로 닫는다. */
    public final class Slot implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        private Slot() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        }
    }
}
