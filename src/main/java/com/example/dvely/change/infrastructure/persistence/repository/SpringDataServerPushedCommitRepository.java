package com.example.dvely.change.infrastructure.persistence.repository;

import com.example.dvely.change.infrastructure.persistence.entity.ServerPushedCommitEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataServerPushedCommitRepository
        extends JpaRepository<ServerPushedCommitEntity, ServerPushedCommitEntity.Key> {

    List<ServerPushedCommitEntity> findByRepositoryAndCommitShaIn(String repository, Collection<String> commitShas);
}
