package com.example.dvely.project.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.project.application.result.CommitResult;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class ProjectMapperTest {

    private final ProjectMapper mapper = new ProjectMapper();

    @Test
    void commitResponseIncludesRelativeTime() {
        var response = mapper.toCommitResponse(new CommitResult(
                "abc123",
                "feat: overview",
                "octo",
                OffsetDateTime.now().minusHours(2)
        ));

        assertThat(response.relativeTime()).isEqualTo("2시간 전");
    }
}
