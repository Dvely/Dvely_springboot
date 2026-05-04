package com.example.dvely.deployment.application.port.out;

public interface GithubRepoPort {

    /**
     * head 브랜치가 base 브랜치보다 앞선 커밋이 있는지 확인한다.
     * true = 새 커밋 있음(merge 필요), false = 동일(merge 불필요)
     */
    boolean hasNewCommits(String userToken, String repoFullName, String base, String head);

    /**
     * head → base PR 생성. 이미 오픈된 PR이 있으면 해당 PR 번호를 반환한다.
     */
    int createOrGetPullRequest(String userToken, String repoFullName,
                               String head, String base, String title);

    /**
     * PR을 merge하고 merge commit SHA를 반환한다.
     */
    String mergePullRequest(String userToken, String repoFullName, int prNumber);

    /**
     * 지정 브랜치의 최신 commit SHA를 반환한다.
     */
    String getHeadCommitSha(String userToken, String repoFullName, String branch);

    /**
     * 해당 commit에 태그가 달려있는지 확인한다.
     */
    boolean isCommitTagged(String userToken, String repoFullName, String commitSha);

    /**
     * 순차 태그(v1, v2, ...)를 생성하고 태그명을 반환한다.
     * 기존 최대 번호에서 +1. 태그가 없으면 v1부터 시작한다.
     */
    String createNextSequentialTag(String userToken, String repoFullName, String commitSha);
}
