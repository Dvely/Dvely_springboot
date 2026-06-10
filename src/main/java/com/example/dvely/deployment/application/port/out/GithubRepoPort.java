package com.example.dvely.deployment.application.port.out;

import com.example.dvely.deployment.domain.value.PackageManager;
import java.time.LocalDateTime;

public interface GithubRepoPort {

    /**
     * 저장소 루트의 lock 파일을 확인해 사용 중인 패키지 매니저를 반환한다.
     * 감지 우선순위: bun.lockb / bun.lock → pnpm-lock.yaml → yarn.lock → package-lock.json → NPM(기본값)
     */
    PackageManager detectPackageManager(String userToken, String repoFullName);

    /**
     * 저장소의 .nvmrc 또는 .node-version 파일을 확인해 Node.js 주 버전 번호를 문자열로 반환한다.
     * 파일이 없거나 파싱 불가 시 "20"을 반환한다.
     */
    String detectNodeVersion(String userToken, String repoFullName);

    /**
     * 저장소의 package.json 의존성과 config 파일을 분석해 프레임워크 타입을 반환한다.
     * 감지 불가 시 null 을 반환한다.
     */
    String detectFrameworkType(String userToken, String repoFullName);

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

    String findSequentialTagForCommit(String userToken, String repoFullName, String commitSha);

    /**
     * 순차 태그(v1, v2, ...)를 생성하고 태그명을 반환한다.
     * 기존 최대 번호에서 +1. 태그가 없으면 v1부터 시작한다.
     */
    String createNextSequentialTag(String userToken, String repoFullName, String commitSha);

    String resolveCommitSha(String userToken, String repoFullName, String ref);

    ReleaseMetadata getReleaseMetadata(
            String userToken,
            String repoFullName,
            String commitSha,
            Integer preferredPrNumber
    );

    record ReleaseMetadata(
            String commitSha,
            String title,
            String description,
            String mergedBy,
            String mergedByAvatarUrl,
            Integer prNumber,
            LocalDateTime mergedAt
    ) {}
}
