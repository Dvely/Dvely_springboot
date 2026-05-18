package com.example.dvely.cloudconnection.domain.repository;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import java.util.List;
import java.util.Optional;

public interface CloudConnectionRepository {

    CloudConnection save(CloudConnection cloudConnection);

    List<CloudConnection> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    Optional<CloudConnection> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    void deleteById(Long id);
}
