package com.example.dvely.cloudconnection.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.application.port.out.CloudConnectionVerificationPort;
import com.example.dvely.cloudconnection.application.port.out.CloudConnectionVerificationPort.VerificationResult;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.model.CloudConnectionVerificationJob;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionVerificationJobRepository;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionVerificationJobStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CloudConnectionVerificationServiceTest {

    @Mock
    private CloudConnectionRepository cloudConnectionRepository;

    @Mock
    private CloudConnectionVerificationJobRepository jobRepository;

    @Mock
    private CloudConnectionVerificationPort verificationPort;

    private CloudConnectionVerificationService service;

    @BeforeEach
    void setUp() {
        service = new CloudConnectionVerificationService(
                cloudConnectionRepository,
                jobRepository,
                verificationPort
        );
    }

    @Test
    void connectedIsSetOnlyAfterProviderVerificationSucceeds() {
        CloudConnection connection = awsConnection(CloudConnectionStatus.VALIDATED);
        CloudConnectionVerificationJob job = runningJob();
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(cloudConnectionRepository.findByIdAndOwnerUserId(10L, 1L))
                .thenReturn(Optional.of(connection));
        when(cloudConnectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(verificationPort.verify(connection)).thenReturn(new VerificationResult(
                CloudConnectionStatus.CONNECTED,
                "verified"
        ));

        service.executeQueued(job.getId());

        ArgumentCaptor<CloudConnectionVerificationJob> jobCaptor =
                ArgumentCaptor.forClass(CloudConnectionVerificationJob.class);
        verify(jobRepository).save(jobCaptor.capture());
        assertThat(connection.getStatus()).isEqualTo(CloudConnectionStatus.CONNECTED);
        assertThat(jobCaptor.getValue().getStatus())
                .isEqualTo(CloudConnectionVerificationJobStatus.SUCCEEDED);
        assertThat(jobCaptor.getValue().getConnectionStatus())
                .isEqualTo(CloudConnectionStatus.CONNECTED);
    }

    private CloudConnectionVerificationJob runningJob() {
        return new CloudConnectionVerificationJob(
                "9d96a123-7414-4075-aa43-a3180bf4d639",
                10L,
                1L,
                CloudConnectionVerificationJobStatus.RUNNING,
                CloudConnectionStatus.VERIFYING,
                "verifying",
                1,
                "worker",
                LocalDateTime.now().plusMinutes(2),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                LocalDateTime.now()
        );
    }

    private CloudConnection awsConnection(CloudConnectionStatus status) {
        return new CloudConnection(
                10L,
                1L,
                CloudProvider.AWS,
                "production",
                "123456789012",
                "ap-northeast-2",
                null,
                "ACCESS_KEY",
                "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD",
                null,
                null,
                null,
                null,
                null,
                status,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}
