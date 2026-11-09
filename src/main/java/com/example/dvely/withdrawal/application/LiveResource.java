package com.example.dvely.withdrawal.application;

/**
 * 탈퇴를 막는, 사용자 AWS 계정에 아직 살아 있는 자원 하나(#613).
 *
 * <p>탈퇴하면 이 자원을 Qeploy 에서 볼 수도 끌 수도 없게 되는데 과금은 계속된다(#595 와 같은 이유). 그래서 먼저 정리하게
 * 하고, 무엇이 남았는지 이 목록으로 알려 준다. 자원 자체는 사용자 소유라 탈퇴가 지우지 않는다.</p>
 *
 * @param type      자원 종류
 * @param id        Qeploy 안의 식별자(서버·DB·도메인·정리 작업 번호)
 * @param projectId 속한 프로젝트. CDN 정리 작업처럼 프로젝트가 없으면 {@code null}
 * @param label     사람이 알아볼 이름(인스턴스 id, DB 엔진, 도메인 이름 등). 없으면 {@code null}
 * @param status    Qeploy 가 기록한 상태
 */
public record LiveResource(Type type, long id, Long projectId, String label, String status) {

    public enum Type {
        /** EC2 인스턴스(EIP 포함). */
        SERVER,
        /** RDS 또는 EC2 위 Docker DB. */
        DATABASE,
        /** S3 HTTPS 도메인의 CloudFront 배포. */
        CDN,
        /** 아직 끝나지 않은 CloudFront·인증서 정리 작업. 클라우드 연결이 지워지면 마무리할 수 없다. */
        CDN_DELETION
    }
}
