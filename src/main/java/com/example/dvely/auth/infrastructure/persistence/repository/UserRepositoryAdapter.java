package com.example.dvely.auth.infrastructure.persistence.repository;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.auth.infrastructure.persistence.entity.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

    private final SpringDataUserRepository springDataUserRepository;

    @Override
    public Optional<User> findByGithubId(GithubId githubId) {
        return springDataUserRepository.findByGithubId(githubId.value())
                .map(UserEntity::toDomain);
    }

    @Override
    public User save(User user) {
        UserEntity entity = springDataUserRepository.findByGithubId(user.getGithubId().value())
                .orElseGet(() -> UserEntity.from(user));

        entity.updateUsername(user.getUsername());
        return springDataUserRepository.save(entity).toDomain();
    }
}
