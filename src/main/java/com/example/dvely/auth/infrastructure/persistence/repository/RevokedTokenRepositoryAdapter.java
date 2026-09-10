package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.application.port.out.TokenBlacklistPort;
import com.example.dvely.auth.infrastructure.cache.RevokedTokenCache;
import com.example.dvely.auth.infrastructure.persistence.entity.RevokedTokenEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 폐기 목록의 정본은 이 어댑터가 감싼 테이블이고, {@link RevokedTokenCache} 는 그 앞에 놓인 단축
 * 경로일 뿐이다. 캐시가 모른다고 답하면 반드시 여기서 DB 를 본다 — 재기동 직후처럼 캐시가 비어 있을
 * 때 DB 를 건너뛰면 폐기된 토큰이 전부 되살아난다.
 */
@Repository
@RequiredArgsConstructor
public class RevokedTokenRepositoryAdapter implements TokenBlacklistPort {

    private final SpringDataRevokedTokenRepository springDataRevokedTokenRepository;
    private final RevokedTokenCache cache;

    @Override
    public void revoke(String jti, LocalDateTime expiresAt) {
        // 캐시를 DB 저장보다 먼저 갱신한다. 이 호출은 @Transactional 인 로그아웃 안에서 일어나므로
        // 행이 실제로 커밋되는 것은 나중인데, 그 사이에 같은 토큰으로 들어온 요청이 통과해서는 안 된다.
        // 트랜잭션이 되감기면 폐기되지 않은 토큰을 폐기됐다고 보게 되지만, 그건 사용자가 다시
        // 로그인하면 끝나는 오류다 - 반대 방향은 되돌릴 방법이 없다.
        cache.rememberRevoked(jti, expiresAt);
        springDataRevokedTokenRepository.save(new RevokedTokenEntity(jti, expiresAt));
    }

    @Override
    public boolean isRevoked(String jti) {
        Boolean cached = cache.lookup(jti);
        if (cached != null) {
            return cached;
        }

        Optional<RevokedTokenEntity> found = springDataRevokedTokenRepository.findByJti(jti);
        if (found.isPresent()) {
            cache.rememberRevoked(jti, found.get().getExpiresAt());
            return true;
        }
        cache.rememberNotRevoked(jti);
        return false;
    }
}
