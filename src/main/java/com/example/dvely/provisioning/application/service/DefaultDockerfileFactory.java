package com.example.dvely.provisioning.application.service;

/**
 * DOCKER 배포 모드에서 <b>저장소 루트에 Dockerfile 이 없을 때</b> 스택을 감지해 기본 Dockerfile 을
 * 만들어주는 폴백. "Dockerfile 만 있으면 무엇이든 배포"가 DOCKER 모드의 본질이고, 이 폴백은 흔한
 * 백엔드 스택(Java/Node)을 Dockerfile 없이도 받게 해준다 — 최선노력(best-effort)이며, 앱이 관례를
 * 벗어나면(비표준 start 스크립트·빌드 산출물 경로) 빌드가 실패해 사용자에게 로그가 그대로 보인다.
 *
 * <p>감지·템플릿은 순수 함수라 컨테이너 없이 단위테스트한다. 컨테이너 안 파일 존재 여부만
 * {@link DockerImageBuildService} 가 조사해 {@link #decide}에 넘긴다.</p>
 */
final class DefaultDockerfileFactory {

    private DefaultDockerfileFactory() {
    }

    enum Stack { GRADLE, MAVEN, NEXT, NODE }

    /**
     * 루트 마커로 스택을 고른다. <b>백엔드 배포 맥락</b>이라 루트의 Java 빌드파일(gradle/maven)이
     * package.json 보다 우선한다 — Spring Boot 백엔드면 build.gradle 이 명확한 신호이고,
     * package.json 은 프론트 도구용으로 섞여 있을 수 있다. Java 빌드파일이 없을 때만 package.json 으로
     * Node/Next 를 가른다(next 의존성/설정이 있으면 Next). 넷 다 아니면 null(→ 호출자가 명확히 실패).
     */
    static Stack decide(boolean gradle, boolean maven, boolean packageJson, boolean next) {
        if (gradle) {
            return Stack.GRADLE;
        }
        if (maven) {
            return Stack.MAVEN;
        }
        if (packageJson) {
            return next ? Stack.NEXT : Stack.NODE;
        }
        return null;
    }

    /** 감지된 스택의 기본 Dockerfile. amd64(EC2) 로 buildx 가 빌드한다. 앱은 컨테이너에서 host 와 같은 포트로 리슨. */
    static String dockerfileFor(Stack stack) {
        return switch (stack) {
            case GRADLE -> GRADLE_DOCKERFILE;
            case MAVEN -> MAVEN_DOCKERFILE;
            case NODE -> NODE_DOCKERFILE;
            case NEXT -> NEXT_DOCKERFILE;
        };
    }

    // Gradle(주로 Spring Boot): build → libs 의 부트 jar(=`-plain` 아님)을 app.jar 로. 포트는 앱이
    // SERVER_PORT env 로 받는다(Spring relaxed-binding: SERVER_PORT → server.port). 테스트는 제외.
    private static final String GRADLE_DOCKERFILE = """
            FROM eclipse-temurin:21-jdk AS build
            WORKDIR /app
            COPY . .
            RUN chmod +x ./gradlew && ./gradlew clean build -x test --no-daemon
            FROM eclipse-temurin:21-jre
            WORKDIR /app
            COPY --from=build /app/build/libs/*.jar /app/
            RUN set -e; jar=$(ls /app/*.jar | grep -v -- '-plain.jar' | head -n1); mv "$jar" /app/app.jar
            ENTRYPOINT ["java","-jar","/app/app.jar"]
            """;

    // Maven(주로 Spring Boot): mvnw 로 package(테스트 제외) → target 의 repackaged jar(`.original` 아님)을
    // app.jar 로. mvn 은 이미지에 없어 래퍼(mvnw)에 의존한다(대부분 커밋됨).
    private static final String MAVEN_DOCKERFILE = """
            FROM eclipse-temurin:21-jdk AS build
            WORKDIR /app
            COPY . .
            RUN chmod +x ./mvnw && ./mvnw -q -DskipTests package
            FROM eclipse-temurin:21-jre
            WORKDIR /app
            COPY --from=build /app/target/*.jar /app/
            RUN set -e; jar=$(ls /app/*.jar | head -n1); mv "$jar" /app/app.jar
            ENTRYPOINT ["java","-jar","/app/app.jar"]
            """;

    // Node: 의존성 설치(lock 있으면 ci, 없으면 install) → build 스크립트 있으면 실행 → npm start.
    // 앱은 PORT env 로 리슨(Node 관례) — 러너가 SERVER_PORT 와 함께 PORT 도 주입한다. devDeps 를
    // 남기는 건 build(예: tsc)가 필요할 수 있어서다(폴백은 크기보다 정확성 우선).
    private static final String NODE_DOCKERFILE = """
            FROM node:20-alpine
            WORKDIR /app
            COPY package*.json ./
            RUN npm ci 2>/dev/null || npm install
            COPY . .
            RUN npm run build --if-present
            ENTRYPOINT ["npm","start"]
            """;

    // Next.js: build 필수(devDeps 필요) → next start(=npm start). next start 는 PORT env 로 리슨한다.
    private static final String NEXT_DOCKERFILE = """
            FROM node:20-alpine
            WORKDIR /app
            COPY package*.json ./
            RUN npm ci 2>/dev/null || npm install
            COPY . .
            RUN npm run build
            ENTRYPOINT ["npm","start"]
            """;
}
