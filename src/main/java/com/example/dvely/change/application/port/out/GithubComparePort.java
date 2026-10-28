package com.example.dvely.change.application.port.out;

import java.util.List;

/**
 * 결과 승인 반영 범위(#532)를 계산하려고 GitHub 에서 두 브랜치의 차이를 읽는다. 읽기 전용이다.
 */
public interface GithubComparePort {

    /**
     * {@code base...head} 비교. head 브랜치가 없으면 비어 있는 결과를 돌려준다(아직 아무것도 올리지 않은 상태).
     *
     * @throws IllegalStateException 비교하지 못했다(공통 조상 없음, GitHub 오류 등)
     */
    Comparison compare(String userToken, String repoFullName, String base, String head);

    /** 커밋 하나가 바꾼 파일 경로(첫 부모 기준). 이름이 바뀐 파일은 새 경로와 예전 경로를 모두 담는다. */
    List<String> commitFiles(String userToken, String repoFullName, String sha);

    /**
     * @param commits      head 에만 있는 커밋. GitHub 가 주는 순서(오래된 것부터) 그대로이며 최대 250개다
     * @param totalCommits head 에만 있는 커밋의 실제 개수. {@code commits} 보다 많으면 목록이 잘린 것이다
     * @param files        base 와 head 의 merge-base 기준 파일 변경. GitHub 상한(300개)에서 잘린다
     */
    record Comparison(List<Commit> commits, int totalCommits, List<File> files) {

        public static Comparison empty() {
            return new Comparison(List.of(), 0, List.of());
        }
    }

    record Commit(String sha, String message, List<String> parents) {
    }

    /**
     * @param status    added | modified | removed | renamed
     * @param additions 바이너리처럼 GitHub 가 줄 수를 세지 않은 파일은 null
     */
    record File(String path, String status, Integer additions, Integer deletions, String previousPath) {
    }
}
