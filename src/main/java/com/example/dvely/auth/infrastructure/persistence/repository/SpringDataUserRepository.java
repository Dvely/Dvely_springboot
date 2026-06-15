package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataUserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByGithubId(String githubId);
    Optional<UserEntity> findByGithubInstallationId(Long githubInstallationId);
}
