package com.example.dvely.provisioning.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.provisioning.application.service.DefaultDockerfileFactory.Stack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultDockerfileFactoryTest {

    @Test
    @DisplayName("루트 Java 빌드파일이 package.json 보다 우선한다(백엔드 배포 맥락)")
    void javaWinsOverPackageJson() {
        // mono 풀스택처럼 둘 다 있어도 백엔드는 Java 로 본다
        assertThat(DefaultDockerfileFactory.decide(true, false, true, true)).isEqualTo(Stack.GRADLE);
        assertThat(DefaultDockerfileFactory.decide(false, true, true, false)).isEqualTo(Stack.MAVEN);
    }

    @Test
    @DisplayName("gradle 이 maven 보다 우선(둘 다 있으면 gradle)")
    void gradleBeatsMaven() {
        assertThat(DefaultDockerfileFactory.decide(true, true, false, false)).isEqualTo(Stack.GRADLE);
    }

    @Test
    @DisplayName("Java 빌드파일이 없으면 package.json 으로 Node/Next 를 가른다")
    void nodeVsNext() {
        assertThat(DefaultDockerfileFactory.decide(false, false, true, false)).isEqualTo(Stack.NODE);
        assertThat(DefaultDockerfileFactory.decide(false, false, true, true)).isEqualTo(Stack.NEXT);
    }

    @Test
    @DisplayName("아무 마커도 없으면 null(→ 호출자가 명확히 실패)")
    void unknownStackIsNull() {
        assertThat(DefaultDockerfileFactory.decide(false, false, false, false)).isNull();
        // package.json 없이 next 플래그만 있는 건 불가능한 조합이지만 방어적으로 null
        assertThat(DefaultDockerfileFactory.decide(false, false, false, true)).isNull();
    }

    @Test
    @DisplayName("각 스택 Dockerfile 은 그 스택 고유의 빌드·실행 커맨드를 담는다")
    void dockerfilesCarryStackSpecificCommands() {
        assertThat(DefaultDockerfileFactory.dockerfileFor(Stack.GRADLE))
                .contains("gradlew").contains("build").contains("app.jar");
        assertThat(DefaultDockerfileFactory.dockerfileFor(Stack.MAVEN))
                .contains("mvnw").contains("package").contains("app.jar");
        assertThat(DefaultDockerfileFactory.dockerfileFor(Stack.NODE))
                .contains("npm ci").contains("\"npm\",\"start\"");
        assertThat(DefaultDockerfileFactory.dockerfileFor(Stack.NEXT))
                .contains("npm run build").contains("\"npm\",\"start\"");
    }

    @Test
    @DisplayName("모든 스택 Dockerfile 은 비어있지 않고 FROM 으로 시작한다")
    void everyDockerfileIsValidShape() {
        for (Stack s : Stack.values()) {
            String df = DefaultDockerfileFactory.dockerfileFor(s);
            assertThat(df).isNotBlank();
            assertThat(df.stripLeading()).startsWith("FROM ");
        }
    }
}
