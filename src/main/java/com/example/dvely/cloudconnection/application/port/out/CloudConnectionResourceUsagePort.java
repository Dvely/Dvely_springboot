package com.example.dvely.cloudconnection.application.port.out;

/**
 * 연결로 다뤄야 하는 사용자 AWS 자원이 아직 남았는지 묻는다(#495). 자원 테이블은 연결을 외래키로
 * 참조하지 않아서 DB 가 막아 주지 않는다 — 연결을 지우면 그 자원은 Qeploy 에서 다시는 지울 수 없다.
 */
public interface CloudConnectionResourceUsagePort {

    ResourceUsage usageOf(Long cloudConnectionId);

    record ResourceUsage(long databases, long servers, long cdnDomains, long pendingCdnDeletions) {

        public boolean isEmpty() {
            return databases == 0 && servers == 0 && cdnDomains == 0 && pendingCdnDeletions == 0;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            append(sb, "DB", databases);
            append(sb, "서버", servers);
            append(sb, "CloudFront 도메인", cdnDomains);
            append(sb, "정리 대기 중인 CDN", pendingCdnDeletions);
            return sb.toString();
        }

        private static void append(StringBuilder sb, String label, long count) {
            if (count > 0) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(label).append(' ').append(count).append("개");
            }
        }
    }
}
