package com.example.dvely.agent.infrastructure.persistence.repository;

import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEventEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAgentRunEventRepository extends JpaRepository<AgentRunEventEntity, Long> {

    List<AgentRunEventEntity> findByTaskIdAndIdGreaterThanOrderByIdAsc(String taskId, Long afterEventId);

    /** 이 태스크에서 가장 최근에 단계 정보를 단 이벤트 — 진행 줄(#600)이 같은 단계로 묶이게 */
    Optional<AgentRunEventEntity> findFirstByTaskIdAndStepIndexIsNotNullOrderByIdDesc(String taskId);
}
