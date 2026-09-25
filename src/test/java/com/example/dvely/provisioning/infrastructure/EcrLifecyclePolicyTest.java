package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver.AwsAccess;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.PutLifecyclePolicyRequest;
import software.amazon.awssdk.services.ecr.model.RepositoryAlreadyExistsException;

/**
 * #344 9-3 회귀망. 이 경로는 항상 {@code :latest} 로 push 하므로 배포마다 직전 이미지가 태그를 잃고
 * 남는다 — lifecycle 정책이 없으면 프로젝트당 이미지가 무한히 쌓이고 ECR 스토리지가 실제 청구서가 된다.
 */
class EcrLifecyclePolicyTest {

    private final AwsCredentialsResolver resolver = mock(AwsCredentialsResolver.class);
    private final EcrClient ecr = mock(EcrClient.class);

    private EcrImageRegistry registryWith(EcrClient client) {
        when(resolver.resolve(any(CloudConnection.class))).thenReturn(mock(AwsAccess.class));
        return new EcrImageRegistry(resolver) {
            @Override
            EcrClient client(AwsAccess access) {
                return client;
            }
        };
    }

    private PutLifecyclePolicyRequest capturedPolicy() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<PutLifecyclePolicyRequest.Builder>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(ecr).putLifecyclePolicy(captor.capture());
        PutLifecyclePolicyRequest.Builder builder = PutLifecyclePolicyRequest.builder();
        captor.getValue().accept(builder);
        return builder.build();
    }

    @Test
    @DisplayName("저장소를 새로 만들면 lifecycle 정책을 건다")
    void appliesPolicyOnNewRepository() {
        EcrImageRegistry registry = registryWith(ecr);

        registry.ensureRepository(mock(CloudConnection.class), 7L);

        PutLifecyclePolicyRequest request = capturedPolicy();
        assertThat(request.repositoryName()).isEqualTo("qeploy-app-7");
        assertThat(request.lifecyclePolicyText())
                .as("태그 없는 이미지를 만료시켜야 한다 — :latest 만 쓰므로 직전 이미지가 태그를 잃는다")
                .contains("\"tagStatus\": \"untagged\"")
                .contains("\"type\": \"expire\"");
    }

    @Test
    @DisplayName("이미 있던 저장소에도 건다 — 실제로 쌓이고 있는 쪽이 그쪽이다")
    void appliesPolicyToRepositoriesThatAlreadyExist() {
        // 이 변경 전에 만들어진 저장소가 이미지를 쌓고 있는 것들이다. 생성 경로만 다루면
        // 고쳐야 할 대상을 정확히 비껴간다.
        doThrow(RepositoryAlreadyExistsException.builder().message("exists").build())
                .when(ecr).createRepository(any(Consumer.class));
        EcrImageRegistry registry = registryWith(ecr);

        registry.ensureWebRepository(mock(CloudConnection.class), 7L);

        assertThat(capturedPolicy().repositoryName()).isEqualTo("qeploy-web-7");
    }

    @Test
    @DisplayName("lifecycle 적용이 실패해도 배포를 막지 않는다")
    void lifecycleFailureDoesNotBreakTheDeploy() {
        // 자격은 사용자 BYOC 계정이다. 이 변경 전에 IAM 정책을 붙인 사용자에게는
        // ecr:PutLifecyclePolicy 가 없어 반드시 실패한다 — 그 배포를 깨뜨리면 안 된다.
        doThrow(new RuntimeException("User is not authorized to perform: ecr:PutLifecyclePolicy"))
                .when(ecr).putLifecyclePolicy(any(Consumer.class));
        EcrImageRegistry registry = registryWith(ecr);

        assertThatCode(() -> registry.ensureRepository(mock(CloudConnection.class), 7L))
                .doesNotThrowAnyException();
    }
}
