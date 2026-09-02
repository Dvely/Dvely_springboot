package com.example.dvely.preview.application.service;

import com.example.dvely.agent.infrastructure.docker.DockerContainerService;
import com.example.dvely.preview.domain.value.PreviewRuntimeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 클론된 프로젝트 내용을 컨테이너 안에서 들여다보고 런타임 타입 기본값을 추정한다. 어디까지나
 * "명시 설정이 없을 때의 기본값"이라, 사용자가 설정으로 언제든 덮어쓴다.
 *
 * <ul>
 *   <li>build.gradle / pom.xml 있음 → JAVA_FULLSTACK
 *   <li>package.json 에 start 스크립트 + 알려진 서버 프레임워크(next·express·nest…) → NODE_SERVER
 *   <li>그 외 → STATIC (지금까지의 기본 동작)
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreviewRuntimeDetector {

    private static final String APP_DIR = "/workspace/app";

    private final DockerContainerService dockerService;

    public PreviewRuntimeType detect(String containerId) {
        if (fileExists(containerId, APP_DIR + "/build.gradle")
                || fileExists(containerId, APP_DIR + "/build.gradle.kts")
                || fileExists(containerId, APP_DIR + "/pom.xml")) {
            return PreviewRuntimeType.JAVA_FULLSTACK;
        }
        if (looksLikeNodeServer(containerId)) {
            return PreviewRuntimeType.NODE_SERVER;
        }
        return PreviewRuntimeType.STATIC;
    }

    private boolean fileExists(String containerId, String path) {
        return "yes".equals(dockerService.exec(containerId,
                "[ -f " + path + " ] && echo yes || echo no").trim());
    }

    /**
     * package.json 에 start 스크립트가 있고, 의존성에 알려진 서버 프레임워크가 잡히면 서버로 본다.
     * 정적 SPA(Vite/CRA)는 build 스크립트만 있고 서버 의존성이 없어 여기 안 걸린다. node 로 파싱해
     * 문자열 grep 의 오탐(주석·이름 부분일치)을 피한다 — 컨테이너에 node 는 반드시 있다.
     */
    private boolean looksLikeNodeServer(String containerId) {
        String script = "node -e \""
                + "try{const p=require('" + APP_DIR + "/package.json');"
                + "const s=p.scripts||{};"
                + "const d=Object.assign({},p.dependencies,p.devDependencies);"
                + "const servers=['next','express','koa','fastify','@nestjs/core','hapi','@hapi/hapi'];"
                + "const hasServer=servers.some(k=>d[k]);"
                + "process.stdout.write((s.start&&hasServer)?'yes':'no')}"
                + "catch(e){process.stdout.write('no')}\"";
        return "yes".equals(dockerService.exec(containerId, script).trim());
    }
}
