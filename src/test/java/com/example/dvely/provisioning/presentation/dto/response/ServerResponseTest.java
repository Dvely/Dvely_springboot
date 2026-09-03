package com.example.dvely.provisioning.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.provisioning.domain.model.ProvisionedServer;
import com.example.dvely.provisioning.domain.value.ServerStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ServerResponseTest {

    private ProvisionedServer server(ServerStatus status, String host) {
        return new ProvisionedServer(1L, 7L, "t3.micro", status, 2L, "i-1", host, 8080,
                null, null, null, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void domainUrlAndUrlWhenRunningWithDomain() {
        ServerResponse r = ServerResponse.from(server(ServerStatus.RUNNING, "3.35.182.236"), "be-test.qeploy.com");
        assertThat(r.url()).isEqualTo("http://3.35.182.236:8080");
        assertThat(r.domainUrl()).isEqualTo("https://be-test.qeploy.com");
    }

    @Test
    void domainUrlNullWhenNoDomain() {
        ServerResponse r = ServerResponse.from(server(ServerStatus.RUNNING, "3.35.182.236"), null);
        assertThat(r.url()).isEqualTo("http://3.35.182.236:8080");
        assertThat(r.domainUrl()).isNull();
    }

    @Test
    void bothNullWhenNotRunning() {
        ServerResponse r = ServerResponse.from(server(ServerStatus.TERMINATED, "3.35.182.236"), "be-test.qeploy.com");
        assertThat(r.url()).isNull();
        assertThat(r.domainUrl()).isNull();
    }
}
