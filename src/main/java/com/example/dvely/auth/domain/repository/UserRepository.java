package com.example.dvely.auth.domain.repository;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.value.GithubId;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findByGithubId(GithubId githubId);
    Optional<User> findById(Long id);
    Optional<User> findByGithubInstallationId(Long githubInstallationId);
    User save(User user);
}
