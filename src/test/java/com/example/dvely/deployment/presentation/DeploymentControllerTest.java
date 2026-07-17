package com.example.dvely.deployment.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.deployment.application.facade.DeploymentFacade;
import com.example.dvely.deployment.application.result.DeploymentFailureAnalysisResult;
import com.example.dvely.deployment.application.result.DeployResult;
import com.example.dvely.deployment.presentation.dto.response.DeploymentFailureAnalysisResponse;
import com.example.dvely.deployment.presentation.dto.response.DeployResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers the 3 endpoints added for U6 (#48): retry, failure-analysis POST/GET. */
@ExtendWith(MockitoExtension.class)
class DeploymentControllerTest {

    @Mock
    private DeploymentFacade deploymentFacade;

    @InjectMocks
    private DeploymentController controller;

    @Test
    void retryDeployment_delegatesUsingAuthenticatedUserIdAndDeploymentId() {
        DeployResult result = new DeployResult(52L, 11L, "VERSION", "v3", "PENDING", null, LocalDateTime.now());
        when(deploymentFacade.retryDeployment(1L, 51L)).thenReturn(result);

        DeployResponse response = controller.retryDeployment(1L, 51L);

        assertThat(response.deploymentId()).isEqualTo(52L);
        assertThat(response.status()).isEqualTo("PENDING");
        verify(deploymentFacade).retryDeployment(1L, 51L);
    }

    @Test
    void analyzeFailure_delegatesAndMapsAllFields() {
        DeploymentFailureAnalysisResult result = new DeploymentFailureAnalysisResult(
                51L, "요약", "발췌", "수정안", "LLM", LocalDateTime.now()
        );
        when(deploymentFacade.analyzeFailure(1L, 51L)).thenReturn(result);

        DeploymentFailureAnalysisResponse response = controller.analyzeFailure(1L, 51L);

        assertThat(response.deploymentId()).isEqualTo(51L);
        assertThat(response.summary()).isEqualTo("요약");
        assertThat(response.logExcerpt()).isEqualTo("발췌");
        assertThat(response.suggestedFix()).isEqualTo("수정안");
        assertThat(response.analysisSource()).isEqualTo("LLM");
        verify(deploymentFacade).analyzeFailure(1L, 51L);
    }

    @Test
    void getFailureAnalysis_delegatesUsingAuthenticatedUserIdAndDeploymentId() {
        DeploymentFailureAnalysisResult result = new DeploymentFailureAnalysisResult(
                51L, "요약", "발췌", "수정안", "RULE_BASED", LocalDateTime.now()
        );
        when(deploymentFacade.getFailureAnalysis(1L, 51L)).thenReturn(result);

        DeploymentFailureAnalysisResponse response = controller.getFailureAnalysis(1L, 51L);

        assertThat(response.analysisSource()).isEqualTo("RULE_BASED");
        verify(deploymentFacade).getFailureAnalysis(1L, 51L);
    }
}
