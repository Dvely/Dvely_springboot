package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.domain.value.CloudConnectionStatus;
import com.example.dvely.cloudconnection.domain.value.CloudProvider;
import com.example.dvely.provisioning.infrastructure.EcrImageRegistry.EcrAuth;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EcrImageRegistryTest {

    private final EcrImageRegistry registry = new EcrImageRegistry(null);

    private CloudConnection connection() {
        return new CloudConnection(11L, 7L, CloudProvider.AWS, "production", "123456789012",
                "ap-northeast-2", null, "ACCESS_KEY", "AKIA1234567890ABCDEF",
                "abcdefghijklmnopqrstuvwxyz1234567890ABCD", null, null, null, null, null,
                CloudConnectionStatus.CONNECTED, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void repositoryNameIsPerProjectAndStable() {
        assertThat(EcrImageRegistry.repositoryNameFor(7L)).isEqualTo("qeploy-app-7");
    }

    @Test
    void registryAndImageRefAreBuiltFromAccountAndRegion() {
        CloudConnection c = connection();
        assertThat(registry.registryFor(c)).isEqualTo("123456789012.dkr.ecr.ap-northeast-2.amazonaws.com");
        assertThat(registry.imageRefFor(c, 7L))
                .isEqualTo("123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/qeploy-app-7:latest");
    }

    @Test
    void decodesEcrTokenIntoUserAndPassword() {
        // ECR authorizationToken = base64("AWS:<password>")
        String password = "abc:def:ghi";   // 비밀번호에 콜론이 있어도 첫 콜론에서만 나눠야 한다
        String token = Base64.getEncoder().encodeToString(("AWS:" + password).getBytes(StandardCharsets.UTF_8));

        EcrAuth auth = EcrImageRegistry.decodeAuthorizationToken(token, "reg.example.com");

        assertThat(auth.registry()).isEqualTo("reg.example.com");
        assertThat(auth.username()).isEqualTo("AWS");
        assertThat(auth.password()).isEqualTo(password);
    }

    @Test
    void rejectsMalformedToken() {
        String noColon = Base64.getEncoder().encodeToString("no-colon-here".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> EcrImageRegistry.decodeAuthorizationToken(noColon, "reg"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EcrImageRegistry.decodeAuthorizationToken("", "reg"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EcrImageRegistry.decodeAuthorizationToken(null, "reg"))
                .isInstanceOf(IllegalStateException.class);
    }
}
