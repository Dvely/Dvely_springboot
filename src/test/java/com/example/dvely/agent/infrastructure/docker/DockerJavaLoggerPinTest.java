package com.example.dvely.agent.infrastructure.docker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code application.yaml} 의 {@code com.github.dockerjava.core.command: WARN} 핀이 실제로
 * 동작하는지 고정한다.
 *
 * <p>왜 테스트가 필요한가: docker-java 3.7.1 의 {@code AbstrDockerCmd.exec()} 는 명령 객체를
 * {@code LOGGER.debug("Cmd: {}", this)} 로 찍고 그 {@code toString()} 은 모든 필드를 reflection
 * 으로 덤프한다. exec 의 {@code env} 가 그 필드에 있고, 거기에는 프리뷰 DB 비밀번호와
 * {@link ContainerGitCredentials} 가 넘기는 GitHub 토큰이 실린다. 즉 <b>설정 한 줄이 비밀을
 * 막고 있고</b>, 지금까지 그것을 지키는 것은 {@code AGENTS.md} 의 "풀지 말 것" 한 문장뿐이었다.
 *
 * <h2>부모를 DEBUG 로 내리는 것이 이 테스트의 전부다</h2>
 * 그냥 {@code isDebugEnabled()} 가 {@code false} 인지 보는 검사는 <b>아무것도 증명하지 않는다</b>
 * — 기본 레벨이 INFO 이므로 핀이 없어도 모든 로거가 그렇게 답한다. 핀이 막으려는 상황은
 * "누가 docker 를 디버깅하려고 상위 로거를 DEBUG 로 내렸을 때" 이므로, 그 상황을 만들어 놓고
 * 자식이 여전히 조용한지를 봐야 한다. 핀을 지우면 자식이 DEBUG 를 상속해 이 테스트가 깨진다.
 */
@SpringBootTest
class DockerJavaLoggerPinTest {

    /** docker-java 가 실제로 그 debug 줄을 찍는 클래스. 핀의 대상이 이 로거의 부모다. */
    private static final String DUMPING_LOGGER = "com.github.dockerjava.core.command.AbstrDockerCmd";

    private static final String PARENT_LOGGER = "com.github.dockerjava";

    @Test
    void 상위_로거를_DEBUG_로_내려도_명령_덤프는_조용하다() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger parent = context.getLogger(PARENT_LOGGER);
        Level original = parent.getLevel();
        parent.setLevel(Level.DEBUG);
        try {
            // 전제가 성립하는지 먼저 본다 — 부모가 DEBUG 가 아니면 아래 단정은 공짜로 통과한다.
            assertThat(parent.isDebugEnabled())
                    .as("부모 로거를 DEBUG 로 내리지 못했다면 이 테스트는 아무것도 증명하지 못한다")
                    .isTrue();

            assertThat(context.getLogger(DUMPING_LOGGER).isDebugEnabled())
                    .as("application.yaml 의 com.github.dockerjava.core.command: WARN 핀이 풀렸다 — "
                            + "exec env 에 실린 GitHub 토큰과 프리뷰 DB 비밀번호가 로그로 나간다")
                    .isFalse();
        } finally {
            parent.setLevel(original);
        }
    }
}
