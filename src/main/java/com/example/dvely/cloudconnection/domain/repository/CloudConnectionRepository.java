package com.example.dvely.cloudconnection.domain.repository;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import java.util.List;
import java.util.Optional;

public interface CloudConnectionRepository {

    CloudConnection save(CloudConnection cloudConnection);

    List<CloudConnection> findAllByOwnerUserIdOrderByCreatedAtDesc(Long ownerUserId);

    Optional<CloudConnection> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** 소유자 컨텍스트 없이 id 로 조회. 상태 폴링 워커처럼 사용자 요청 밖에서 자격을 얻을 때 쓴다. */
    Optional<CloudConnection> findById(Long id);

    void deleteById(Long id);
}
