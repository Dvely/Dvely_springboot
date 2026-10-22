package com.example.dvely.provisioning.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.dvely.cloudconnection.application.service.AwsCredentialFailureReporter;
import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.repository.CloudConnectionRepository;
import com.example.dvely.provisioning.domain.model.ProvisionedDatabase;
import com.example.dvely.provisioning.domain.repository.ProvisionedDatabaseRepository;
import com.example.dvely.provisioning.domain.value.DatabaseEngine;
import com.example.dvely.provisioning.domain.value.ProvisionMethod;
import com.example.dvely.provisioning.domain.value.ProvisionOrigin;
import com.example.dvely.provisioning.domain.value.ProvisionStatus;
import com.example.dvely.provisioning.infrastructure.RdsProvisioner;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class RdsProvisionStatusWorkerTest {

    @Mock private ProvisionedDatabaseRepository databaseRepository;
    @Mock private CloudConnectionRepository cloudConnectionRepository;
    @Mock private RdsProvisioner rdsProvisioner;
    @Mock private AwsCredentialFailureReporter credentialFailureReporter;
    @InjectMocks private RdsProvisionStatusWorker worker;

    private final Logger logger = (Logger) LoggerFactory.getLogger(RdsProvisionStatusWorker.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private ProvisionedDatabase provisioningRds() {
        ProvisionedDatabase db = new ProvisionedDatabase(12L, 10L, ProvisionMethod.RDS,
                DatabaseEngine.MYSQL, ProvisionOrigin.MANUAL, ProvisionStatus.PROVISIONING, "qeploy-49-x",
                null, 3306, "app", "user", "pw", null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
        db.assignCloudConnection(11L);
        return db;
    }

    private RuntimeException givenDescribeFails(String message) {
        ProvisionedDatabase db = provisioningRds();
        when(databaseRepository.findByStatus(ProvisionStatus.PROVISIONING, 20)).thenReturn(List.of(db));
        when(cloudConnectionRepository.findById(11L)).thenReturn(Optional.of(mock(CloudConnection.class)));
        RuntimeException failure = new RuntimeException(message);
        when(rdsProvisioner.describe(any(), eq("qeploy-49-x"))).thenThrow(failure);
        return failure;
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void anExpiredCredentialIsRecordedOnTheConnectionInsteadOfAWarningEveryThirtySeconds() {
        // dev: one expired temporary credential left this WARN every 30s for over a month (#487).
        RuntimeException failure = givenDescribeFails("The security token included in the request is expired");
        when(credentialFailureReporter.reportIfCredentialFailure(11L, failure)).thenReturn(true);

        worker.pollProvisioning();

        verify(credentialFailureReporter).reportIfCredentialFailure(11L, failure);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void anyOtherFailureStillWarns() {
        // Quieting must not spread: a throttle or a 5xx is still worth a WARN.
        RuntimeException failure = givenDescribeFails("Rate exceeded");
        when(credentialFailureReporter.reportIfCredentialFailure(11L, failure)).thenReturn(false);

        worker.pollProvisioning();

        assertThat(warnings()).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("RDS 상태 폴링 실패", "databaseId=12"));
    }
}
