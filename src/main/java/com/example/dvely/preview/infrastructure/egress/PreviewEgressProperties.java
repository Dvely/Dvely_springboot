package com.example.dvely.preview.infrastructure.egress;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프리뷰 컨테이너의 바깥 통신을 허용목록으로 좁히는 설정 (#332 4단계).
 *
 * <p><b>기본은 꺼짐이다.</b> 허용목록이 좁으면 빌드가 깨지는데, 그 실패는 사용자 프로젝트마다
 * 다르게 나타나 원인을 가리기 어렵다. 실제 프로젝트(정적·Node·JAVA_FULLSTACK)로 태워 목록을
 * 확인한 뒤 켠다.</p>
 *
 * @param enabled              끄면 프리뷰는 지금처럼 공유 브리지에 붙고 바깥이 열려 있다
 * @param proxyImage           프록시 사이드카 이미지. tinyproxy 를 여기에 설치해 띄운다
 * @param proxyName            프록시 컨테이너 이름. <b>모든 프리뷰가 하나를 공유한다</b>
 * @param proxyAlias           프리뷰가 프록시를 부르는 DNS 이름. 고정이라 프록시 URL 도 고정이다
 * @param proxyPort            tinyproxy 수신 포트. 세션마다 뜨는 tinyproxy 프로세스가 모두 같은
 *                             포트를 쓰지만, 세션별로 다른 IP(그 세션 네트워크 쪽 프록시 IP)에만
 *                             {@code Listen} 하므로 서로 충돌하지 않는다
 * @param networkPrefix        프리뷰마다 만드는 전용 네트워크 이름의 접두
 * @param allowedHosts         나갈 수 있는 호스트. 통째로 일치해야 하며 하위 도메인은 열리지 않는다
 * @param perSessionMaxClients 세션 하나가 쓸 수 있는 tinyproxy 동시 접속 상한(#466). 전역이 아니라
 *                             세션마다 걸려서, 한 프리뷰가 이 값만큼 연결을 붙잡아도 다른 세션의
 *                             tinyproxy 프로세스는 영향을 받지 않는다. <b>기본값은 16 으로 고정이다</b>
 *                             — 논문(ACK 2026, KIPS_C2026B0295)에 실측 수치로 보고한 값과 같아야
 *                             하므로 임의로 바꾸지 않는다. 여유는 넉넉하지 않다 — npm 은 기본적으로
 *                             동시 연결을 최대 15개까지 쓰므로(실측 E8b: Vite+React·Next.js 설치 모두
 *                             피크 동시 연결 15, 상한 도달 0회) 16 은 그 위에 딱 1개 여유만 둔다. 그래도
 *                             올려야 한다면 {@code QEPLOY_PREVIEW_EGRESS_PER_SESSION_MAX_CLIENTS}
 *                             환경변수만 바꾸면 된다 — <b>단, proxyPidsLimit·proxyMemoryMb 는 이
 *                             값을 실행 시점에 참조해 자동으로 늘어나지 않는다</b>(둘 다 독립된 설정
 *                             키다). 이 값을 올리면 그 둘도 아래 산식대로 같이 올려야 한다
 * @param proxyPidsLimit       프록시 컨테이너 전체의 cgroup pids 상한. tinyproxy 는 연결마다 스레드를
 *                             쓰는데 스레드도 pids 카운트에 들어간다. 세션마다 프로세스가 하나씩 뜨므로
 *                             필요한 상한은 <b>세션 수 × (perSessionMaxClients + 1)</b> 이상이어야 한다
 *                             — {@code +1} 은 tinyproxy 자신의 메인 스레드다. 실측(E8, 3회): 유휴 연결
 *                             60개를 쥔 세션 하나의 스레드 수가 정확히 {@code 1 + MaxClients(16) = 17}
 *                             이었고, 그동안 다른 세션은 영향 없이 5/5 를 유지했다. 세션 2개가 뜬
 *                             상태의 컨테이너 {@code pids.current} 는 64개 한도 중 22개였다(한 세션만
 *                             포화 상태였을 때). 기본값 272 는 동시 세션 16개가 전부
 *                             {@code perSessionMaxClients} 까지 포화돼도 버티는 상한이다
 *                             (16 세션 × 17 = 272). 세션 상한이나 동시 세션 목표를 올리면 이 값도
 *                             같은 산식으로 다시 계산해야 한다
 * @param proxyMemoryMb        프록시 컨테이너 전체의 메모리 상한(MB). 과거엔 전역 프로세스 하나(연결
 *                             최대 50개)에 128MB 를 줬다. 지금은 세션마다 프로세스가 하나씩 뜨고 각자
 *                             자기 연결 수만큼 스레드를 띄우므로, {@code proxyPidsLimit} 과 같은 스레드
 *                             수 상한에 스레드당 넉넉히 1MB(Alpine/musl 기준 실제 사용량보다 여유 있게
 *                             잡은 값), 거기에 세션별 프로세스 자체의 기본 메모리(코드·버퍼, 세션당
 *                             ~3MB로 추정)를 더했다. 기본값 320 = 272(스레드 1MB×272) + 16세션×3MB(48) 다.
 *                             proxyPidsLimit 를 올리면 이 값도 같은 산식으로 다시 계산해야 한다
 */
@ConfigurationProperties(prefix = "qeploy.preview.egress")
public record PreviewEgressProperties(
        boolean enabled,
        String proxyImage,
        String proxyName,
        String proxyAlias,
        int proxyPort,
        String networkPrefix,
        List<String> allowedHosts,
        int perSessionMaxClients,
        int proxyPidsLimit,
        int proxyMemoryMb
) {

    public String proxyUrl() {
        return "http://" + proxyAlias + ":" + proxyPort;
    }

    /** 이 프리뷰 세션 전용 네트워크 이름. 세션마다 달라야 서로 라우팅되지 않는다. */
    public String networkName(String previewSessionId) {
        return networkPrefix + previewSessionId;
    }
}
