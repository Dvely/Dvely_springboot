package com.example.dvely.agent.infrastructure.docker;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.dvely.preview.infrastructure.egress.PreviewNetworkPolicy;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 프리뷰 세션 네트워크는 컨테이너를 지운 <b>뒤에</b> 되돌린다(#529). 컨테이너가 붙어 있는 동안에는
 * 네트워크 제거가 "사용 중" 으로 실패해 고아가 남는다.
 */
class DockerContainerServiceReleaseOrderTest {

    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final PreviewNetworkPolicy policy = mock(PreviewNetworkPolicy.class);
    private final DockerContainerService service = new DockerContainerService(docker);

    @Test
    void theNetworkIsReleasedAfterTheContainerIsRemoved() {
        service.setPreviewNetworkPolicy(policy);
        when(docker.inspectContainerCmd("c-1").exec().getConfig().getLabels())
                .thenReturn(Map.of("qeploy.previewSessionId", "s-1"));
        RemoveContainerCmd remove = docker.removeContainerCmd("c-1").withForce(true).withRemoveVolumes(true);

        service.removeContainer("c-1");

        InOrder order = inOrder(remove, policy);
        order.verify(remove).exec();
        order.verify(policy).release("s-1");
    }

    @Test
    void theNetworkIsReleasedEvenIfTheContainerIsAlreadyGone() {
        service.setPreviewNetworkPolicy(policy);
        when(docker.inspectContainerCmd("c-1").exec().getConfig().getLabels())
                .thenReturn(Map.of("qeploy.previewSessionId", "s-1"));
        when(docker.stopContainerCmd("c-1").withTimeout(5).exec()).thenThrow(new NotFoundException("gone"));

        service.removeContainer("c-1");

        verify(policy).release("s-1");
    }
}
