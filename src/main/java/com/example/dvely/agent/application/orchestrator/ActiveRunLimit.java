package com.example.dvely.agent.application.orchestrator;

import com.example.dvely.agent.infrastructure.store.TaskStore;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.common.limit.UserLimitExceededException;
import com.example.dvely.common.limit.UserLimitProperties;
import com.example.dvely.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자별 동시 런 상한(#509). 세지 않는 상태에서 세는 상태로 런을 올리는 곳에서 부른다 — 새 런 생성, 사용자 재시도
 * (FAILED), 계획 승인 완료(승인 대기·FAILED → 실행), 되묻기 답(되묻기 대기 → 실행).
 *
 * <p>결과 승인·저장소 연결 승인 뒤의 재개는 부르지 않는다. 그 승인 트랜잭션은 GitHub 반영 중 토큰을 갱신할 수 있고,
 * 갱신은 별도 트랜잭션에서 사용자 행을 잠근다 — 여기서 사용자 행을 쥐고 있으면 자기 자신을 기다린다. 두 승인은 런마다
 * 한 번뿐이고 이미 실행된 런의 남은 단계를 잇는 것이다.</p>
 *
 * <p>셈과 생성 사이에 같은 사용자의 다른 요청이 끼면 둘 다 빈자리를 보고 통과한다. 그래서 사용자 행을 잠가 같은 사용자의
 * 생성을 한 줄로 세운다. 잠금은 호출자 트랜잭션이 끝날 때 풀리므로, 그 트랜잭션 안에서 런을 만들어야 의미가 있다 —
 * 트랜잭션 밖에서 부르면 실패한다(MANDATORY).</p>
 *
 * <p>런 행을 잠금 읽기(FOR SHARE)로 세지 않는다. 그 사용자의 런 행 전부에 공유 잠금이 걸리는데, 런 행을 잠근 채
 * 별도 트랜잭션에서 사용자 행을 잠그는 경로(GitHub 토큰 갱신, REQUIRES_NEW)가 있어 서로 기다리면 DB 가 교착으로
 * 보지 못하고 잠금 대기 시간 초과까지 멈춘다. 그래서 셈은 잠금을 얻은 뒤의 새 트랜잭션에서 한다
 * ({@link TaskStore#countWorkingRuns}).</p>
 */
@Component
@RequiredArgsConstructor
public class ActiveRunLimit {

    private final UserRepository userRepository;
    private final TaskStore taskStore;
    private final UserLimitProperties limits;

    @Transactional(propagation = Propagation.MANDATORY)
    public void check(Long userId) {
        userRepository.findByIdForUpdate(userId);
        long working = taskStore.countWorkingRuns(userId);
        if (working >= limits.maxActiveRuns()) {
            throw new UserLimitExceededException(
                    ErrorCode.ACTIVE_RUN_LIMIT_EXCEEDED,
                    "진행 중인 작업이 " + working + "개라 새 작업을 시작할 수 없습니다(사용자당 "
                            + limits.maxActiveRuns() + "개). 진행 중인 작업이 끝난 뒤 다시 시도해주세요.");
        }
    }
}
