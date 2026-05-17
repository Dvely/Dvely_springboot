package com.example.dvely.cloudconnection.infrastructure.persistence.repository;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.infrastructure.persistence.entity.CloudConnectionEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CloudConnectionRepositoryAdapter implements CloudConnectionRepository {

    private final SpringDataCloudConnectionRepository springDataRepository;

    @Override
    public CloudConnection save(CloudConnection cloudConnection) {
        if (cloudConnection.getId() == null) {
            return springDataRepository.save(CloudConnectionEntity.from(cloudConnection)).toDomain();
        }
        CloudConnectionEntity entity = springDataRepository.findById(cloudConnection.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "클라우드 연결 정보를 찾을 수 없습니다. cloudConnectionId=" + cloudConnection.getId()));
        entity.updateFrom(cloudConnection);
        return springDataRepository.save(entity).toDomain();
    }

    @Override
    public List<CloudConnection> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId) {
        return springDataRepository.findAllByOwnerUserIdOrderByCreatedAtDesc(ownerUserId).stream()
                .map(CloudConnectionEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<CloudConnection> findByIdAndOwnerUserId(Long id, Long ownerUserId) {
        return springDataRepository.findByIdAndOwnerUserId(id, ownerUserId)
                .map(CloudConnectionEntity::toDomain);
    }

    @Override
    public void deleteById(Long id) {
        springDataRepository.deleteById(id);
    }
}
