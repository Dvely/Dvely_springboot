package com.example.dvely.auth.domain.repository;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.value.GithubId;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByGithubId(GithubId githubId);
    Optional<User> findById(Long id);

    /**
     * 유저 행을 잠근 채 읽는다. GitHub 토큰 갱신처럼 "읽고 → 외부에 물어보고 → 쓰는" 구간을
     * 직렬화할 때 쓴다. 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로, 그 트랜잭션 안에서
     * 저장까지 마쳐야 한다.
     */
    Optional<User> findByIdForUpdate(Long id);
    Optional<User> findByGithubInstallationId(Long githubInstallationId);
    User save(User user);
}
