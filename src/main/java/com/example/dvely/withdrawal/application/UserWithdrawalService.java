package com.example.dvely.withdrawal.application;

import com.example.dvely.withdrawal.infrastructure.UserWithdrawalStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴(#613 PR-1). 사용자의 개인정보와 서비스 이용 기록을 DB 에서 지운다 — 처리방침의 "탈퇴하면 지체 없이 파기" 를
 * 코드로 지킨다.
 *
 * <p>트랜잭션 하나다. 사용자 행을 잠그고, 사용자 AWS 계정에 살아 있는 자원이 있으면 아무것도 지우지 않고
 * {@link WithdrawalBlockedException} 으로 그 목록을 돌려준다(과금은 계속되는데 끌 길이 사라지는 것을 막는다, #595). 없으면
 * {@link UserWithdrawalStore} 가 FK 순서대로 지운다. 어느 문장이 실패하면 전부 되돌아간다.</p>
 *
 * <p>지우지 않는 것: 사용자 GitHub 저장소와 AWS 계정의 자원 자체(사용자 소유, 처리방침 4항), GitHub App 설치(사용자가 GitHub 에서
 * 해제한다).</p>
 *
 * <p>여기서는 DB 의 행만 다룬다. 진행 중인 실행·컨테이너 정리와 토큰 폐기, 감사 기록은 {@link UserWithdrawalFacade} 가
 * 이 앞뒤에서 한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserWithdrawalStore store;

    /** 탈퇴를 막을 자원이 있는지 미리 본다(화면 안내용). 잠그지 않는다. */
    @Transactional(readOnly = true)
    public List<LiveResource> findLiveResources(long userId) {
        return store.findLiveResources(userId);
    }

    /**
     * 탈퇴한다. 사용자가 이미 없으면 아무것도 하지 않는다(두 번 눌러도 같은 결과).
     *
     * <p>호출자가 연 트랜잭션이 있으면 그 안에서 돈다 — 엔드포인트는 같은 트랜잭션에서 access 토큰도 폐기한다.</p>
     *
     * @return 이번 호출이 지웠으면 {@code true}, 사용자가 이미 없었으면 {@code false}
     * @throws WithdrawalBlockedException 사용자 AWS 계정에 살아 있는 자원이 남아 있을 때. 아무것도 지우지 않았다
     */
    @Transactional
    public boolean withdraw(long userId) {
        if (!store.lockUser(userId)) {
            return false;
        }
        List<LiveResource> live = store.findLiveResources(userId);
        if (!live.isEmpty()) {
            throw new WithdrawalBlockedException(live);
        }
        store.deleteAll(userId);
        // 회원 번호 외에는 남기지 않는다 — 탈퇴 사실 자체의 기록(감사)은 UserWithdrawalFacade 가 개인정보 없이 남긴다.
        log.info("회원 탈퇴 데이터 삭제 완료: userId={}", userId);
        return true;
    }

    /** 아직 끝나지 않은 에이전트 런. 탈퇴 전에 취소할 대상이다. */
    @Transactional(readOnly = true)
    public List<String> findUnfinishedTaskIds(long userId) {
        return store.findUnfinishedTaskIds(userId);
    }

    /** 우리 호스트에 띄운 프리뷰 테스트 DB 컨테이너. 탈퇴가 직접 지울 대상이다. */
    @Transactional(readOnly = true)
    public List<String> findLocalDatabaseContainerIds(long userId) {
        return store.findLocalDatabaseContainerIds(userId);
    }
}
