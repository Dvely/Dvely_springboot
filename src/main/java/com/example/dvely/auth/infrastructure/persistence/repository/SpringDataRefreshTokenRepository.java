package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SpringDataRefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByToken(String token);

    @Modifying
    @Query("UPDATE RefreshTokenEntity r SET r.revoked = true WHERE r.userId = :userId")
    void revokeAllByUserId(@Param("userId") Long userId);

    // idx_refresh_tokens_expires_at (V61) 을 탄다.
    @Modifying
    @Query("DELETE FROM RefreshTokenEntity r WHERE r.expiresAt < :now")
    int deleteExpired(LocalDateTime now);

    /**
     * 폐기됐지만 아직 만료되지 않은 행. 이것들은 만료일까지(리프레시 토큰 수명만큼) 남아 있었다(#338).
     *
     * <p>지워도 되는 근거: {@code RefreshToken.isValid()} 가 {@code !revoked && now < expiresAt} 라
     * 폐기된 행은 어떤 경우에도 다시 유효해지지 않는다. 재사용 탐지처럼 "폐기된 행을 찾는 것"에
     * 의미를 두는 로직도 없다 — {@code AuthCommandService} 는 행이 없을 때와 폐기됐을 때 모두
     * 같은 {@code UnauthorizedException} 을 던진다.</p>
     *
     * <p>{@link #deleteExpired} 와 한 문장으로 합치지 않은 이유: {@code expiresAt < ?} 에 OR 을
     * 붙이면 인덱스를 못 타 방금 추가한 idx_refresh_tokens_expires_at 이 무의미해진다. revoked 는
     * 값이 둘뿐이라 인덱스를 새로 걸 만한 컬럼이 아니고, 이 문장은 하루 한 번만 돈다.</p>
     */
    @Modifying
    @Query("DELETE FROM RefreshTokenEntity r WHERE r.revoked = true")
    int deleteRevoked();
}
