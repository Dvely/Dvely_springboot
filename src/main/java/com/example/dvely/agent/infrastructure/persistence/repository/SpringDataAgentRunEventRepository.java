package com.example.dvely.agent.infrastructure.persistence.repository;

import com.example.dvely.agent.infrastructure.persistence.entity.AgentRunEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAgentRunEventRepository extends JpaRepository<AgentRunEventEntity, Long> {

    List<AgentRunEventEntity> findByTaskIdAndIdGreaterThanOrderByIdAsc(String taskId, Long afterEventId);
}
