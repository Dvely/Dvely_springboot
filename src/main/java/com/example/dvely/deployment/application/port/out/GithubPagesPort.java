package com.example.dvely.deployment.application.port.out;

public interface GithubPagesPort {

    record PagesInfo(boolean enabled, String url, String sourceBranch, String customDomain) {}

    /**
     * 저장소의 GitHub Pages 활성화 여부 및 현재 설정 조회
     * GET /repos/{owner}/{repo}/pages
     */
    PagesInfo getPages(String userToken, String repoFullName);

    /**
     * GitHub Pages 신규 활성화 후 지정 브랜치로 서빙 시작
     * POST /repos/{owner}/{repo}/pages
     * @return GitHub Pages URL (예: https://{owner}.github.io/{repo}/)
     */
    String enablePages(String userToken, String repoFullName, String branch);

    /**
     * 이미 활성화된 GitHub Pages의 소스 브랜치 변경
     * PUT /repos/{owner}/{repo}/pages
     * @return GitHub Pages URL
     */
    String updatePagesSource(String userToken, String repoFullName, String branch, String customDomain);

    /**
     * 특정 tag가 가리키는 커밋 SHA를 조회해 새 브랜치를 생성한다.
     * VERSION 배포 시 tag → 브랜치 변환에 사용
     * GET /repos/{owner}/{repo}/git/refs/tags/{tagName} → SHA 추출
     * POST /repos/{owner}/{repo}/git/refs → 브랜치 생성
     * @return 생성된 브랜치명
     */
    String createBranchFromTag(String userToken, String repoFullName, String tagName, String branchName);
}
