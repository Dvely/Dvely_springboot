package com.example.dvely.project.domain.value;

import java.util.Locale;

/**
 * 프로젝트 이름에서 GitHub 저장소 이름을 유도하는 단일 규칙.
 *
 * 같은 프로젝트가 어느 경로로 연결되든 같은 이름이 나와야 한다. 배포 스텝의 자동 탐색과
 * 저장소 연결 승인이 서로 다른 이름을 만들면, 한쪽이 방금 만든 저장소를 다른 쪽이 찾지 못하고
 * 저장소를 하나 더 만들게 된다. 양쪽에 복사돼 있던 규칙을 여기로 모았다.
 */
public final class RepositoryNamePolicy {

    private static final String FALLBACK_PREFIX = "qeploy-project-";

    private RepositoryNamePolicy() {
    }

    /**
     * GitHub 저장소 이름으로 쓸 수 있게 정규화한다.
     * 소문자·숫자·하이픈만 남기고, 연속 하이픈을 하나로 접고, 양 끝 하이픈을 떼어낸다.
     *
     * 소문자화에 Locale.ROOT 를 쓰는 게 중요하다. 기본 로케일이면 터키어 환경에서 I 가
     * 점 없는 ı 로 바뀌어, 같은 프로젝트가 서버 로케일에 따라 다른 이름을 갖게 된다.
     *
     * 남는 문자가 없으면 빈 문자열을 돌려준다. 저장소 이름으로 그대로 쓰지 말고 forProject 를
     * 쓰거나 호출부에서 폴백을 처리할 것.
     */
    public static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    /**
     * 프로젝트 이름 기반 저장소 이름.
     * 정규화 후 남는 문자가 없으면(한글로만 된 이름 등) 유저별 폴백 이름을 쓴다.
     * 빈 이름으로 GitHub 생성 API 를 부르면 실패하기 때문이다.
     */
    public static String forProject(String projectName, Long ownerUserId) {
        String sanitized = sanitize(projectName);
        return sanitized.isEmpty() ? FALLBACK_PREFIX + ownerUserId : sanitized;
    }
}
