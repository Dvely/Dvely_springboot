package com.example.dvely.deployment.application.port.out;

public interface GithubActionsPort {

    /**
     * 저장소에 워크플로우 파일이 존재하는지 확인
     * GET /repos/{owner}/{repo}/contents/.github/workflows/{fileName}
     */
    boolean workflowExists(String userToken, String repoFullName, String workflowFileName);

    /**
     * 워크플로우 파일 생성 또는 갱신
     * PUT /repos/{owner}/{repo}/contents/.github/workflows/{fileName}
     */
    void createOrUpdateWorkflow(String userToken, String repoFullName, String workflowFileName, String content);

    /**
     * 워크플로우 dispatch 트리거
     * POST /repos/{owner}/{repo}/actions/workflows/{fileName}/dispatches
     * @param ref 실행 기준 브랜치 또는 태그 (LATEST: default 브랜치, VERSION: 태그명)
     */
    void triggerWorkflow(String userToken, String repoFullName, String workflowFileName, String ref);
}
