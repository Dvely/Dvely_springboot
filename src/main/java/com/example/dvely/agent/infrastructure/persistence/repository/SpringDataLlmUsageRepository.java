package com.example.dvely.agent.infrastructure.persistence.repository;

import com.example.dvely.agent.infrastructure.persistence.entity.LlmUsageEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataLlmUsageRepository extends JpaRepository<LlmUsageEntity, Long> {

    List<LlmUsageEntity> findAllByTaskIdOrderByIdAsc(String taskId);

    /**
     * 이 태스크가 지금까지 쓴 토큰 합계.
     *
     * <p>예산 상한이 <b>재시도를 건너</b> 유효하려면 필요하다. 실행마다 0 에서 다시 세면 상한은
     * 태스크 재시도 횟수만큼 곱해지는데, 그 곱셈을 닫는 것이 이 상한의 목적이다.</p>
     */
    @Query("select coalesce(sum(u.inputTokens + u.outputTokens"
            + " + u.cacheCreationInputTokens + u.cacheReadInputTokens), 0)"
            + " from LlmUsageEntity u where u.taskId = :taskId")
    long sumTotalTokensByTaskId(@Param("taskId") String taskId);
}
