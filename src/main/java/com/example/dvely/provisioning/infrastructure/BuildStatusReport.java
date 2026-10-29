package com.example.dvely.provisioning.infrastructure;

import java.util.Optional;

/**
 * 대상 EC2 빌드(#556)의 상태 자가 보고 계약. 인스턴스의 부팅 스크립트가 쓰고 {@code ProvisionedServerStatusWorker}
 * 가 읽으므로 경로와 값 형식은 여기 한 곳에서 정한다.
 *
 * <p>경로는 {@code /qeploy/build-status/{projectId}/{instanceId}} 다. {@code /qeploy/{projectId}/} 아래에 두지 않는
 * 이유: 부팅 스크립트가 그 경로를 통째로 앱 env 로 내리므로 상태 값이 앱 환경변수에 섞인다. BYOC 정책의 SSM 권한
 * ({@code parameter/qeploy/*})은 그대로 이 경로를 덮는다.</p>
 *
 * <p>값은 첫 줄이 상태이고, {@code BUILD_FAILED} 면 다음 줄부터 빌드 로그 꼬리다. SSM Standard 파라미터 값 상한이
 * 4KB 라 꼬리는 {@link #LOG_TAIL_BYTES} 로 자른다.</p>
 */
public final class BuildStatusReport {

    public static final int LOG_TAIL_BYTES = 3000;

    public enum State { BUILDING, BUILT, BUILD_FAILED }

    public record Status(State state, String logTail) {
    }

    private BuildStatusReport() {
    }

    public static String pathPrefix(Long projectId) {
        return "/qeploy/build-status/" + projectId + "/";
    }

    public static String path(Long projectId, String instanceId) {
        return pathPrefix(projectId) + instanceId;
    }

    /** 첫 줄을 상태로 읽는다. 모르는 값이면 empty — 다른 형식의 값을 상태로 오인하지 않는다. */
    public static Optional<Status> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        int newline = value.indexOf('\n');
        String head = (newline < 0 ? value : value.substring(0, newline)).trim();
        String tail = newline < 0 ? null : value.substring(newline + 1);
        for (State state : State.values()) {
            if (state.name().equals(head)) {
                return Optional.of(new Status(state, tail == null || tail.isBlank() ? null : tail));
            }
        }
        return Optional.empty();
    }
}
