package com.example.dvely.project.application.service;

import com.example.dvely.cloudresource.application.LiveResource;
import java.util.List;

/**
 * 사용자 AWS 계정에 이 프로젝트로 만든 서버·DB 가 살아 있거나 배포가 진행 중이라 프로젝트를 지우지 않았다(#595). 아무것도
 * 지우지 않은 상태다 — GitHub 저장소도 그대로다.
 */
public class ProjectDeletionBlockedException extends RuntimeException {

    private final transient List<LiveResource> liveResources;

    public ProjectDeletionBlockedException(List<LiveResource> liveResources) {
        super("AWS 계정에 이 프로젝트로 만든 자원이 " + liveResources.size()
                + "개 남아 있어 삭제하지 않았습니다. 인프라 탭에서 서버·DB 를 종료하고 진행 중인 배포가 끝난 뒤 다시 삭제하세요.");
        this.liveResources = List.copyOf(liveResources);
    }

    public List<LiveResource> liveResources() {
        return liveResources;
    }
}
