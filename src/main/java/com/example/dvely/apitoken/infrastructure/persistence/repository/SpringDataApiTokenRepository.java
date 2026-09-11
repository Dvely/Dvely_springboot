package com.example.dvely.apitoken.infrastructure.persistence.repository;

import com.example.dvely.apitoken.infrastructure.persistence.entity.ApiTokenEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataApiTokenRepository extends JpaRepository<ApiTokenEntity, Long> {

    Optional<ApiTokenEntity> findByTokenHash(String tokenHash);

    List<ApiTokenEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    long deleteByIdAndUserId(Long id, Long userId);

    // A targeted UPDATE rather than a load-modify-save: this runs on the authentication path, and
    // nothing else about the row is being changed.
    @Modifying
    @Query("update ApiTokenEntity t set t.lastUsedAt = :now where t.id = :id")
    void touchLastUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 만료된 토큰 정리(#338). 만료 시각이 아니라 만료 + 유예 이후를 자르는 이유는, 목록 화면에서
     * "이 토큰은 만료됐다"를 잠시 보여줄 수 있어야 하기 때문이다 — 발급 직후 사라지면 사용자는
     * 왜 인증이 깨졌는지 알 방법이 없다. 유예 기간은 호출자가 cutoff 로 정한다.
     *
     * <p>인덱스를 따로 두지 않았다. api_tokens 는 사용자가 직접 만드는 PAT 이라 로그인마다 쌓이는
     * refresh_tokens 와 규모가 다르고, 이 문장은 하루 한 번 돈다.</p>
     */
    @Modifying
    @Query("delete from ApiTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
