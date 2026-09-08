package com.example.dvely.agent.infrastructure.docker;

/**
 * Qeploy 가 띄우는 컨테이너 안의 <b>약속된 경로</b>. 여러 모듈이 같은 값을 각자 적어 두는 대신
 * 여기 한 번만 적는다.
 *
 * <p>같은 문자열이 일곱 파일에 흩어져 있었고(각자 {@code APP_DIR} 상수를 따로 선언하거나 명령
 * 문자열에 그대로 박아서), 그 약속이 깨졌을 때 두 번 연달아 버그가 나갔다:</p>
 *
 * <ul>
 *   <li>#303 — 프레임워크 없는 프로젝트는 스캐폴더가 안 돌아 코드가 {@code /workspace} 루트에
 *       쌓였다. 저장소 push 가 {@code can't cd to /workspace/app} 으로 죽었는데, 프리뷰는
 *       index.html 을 찾아다니는 폴백이 있어 <b>화면은 멀쩡히 동작해</b> 승인 단계에 와서야 드러났다.</li>
 *   <li>#307 — 같은 계열. 배포 워크플로가 Node 프로젝트를 전제해 정적 사이트에서 죽었다.</li>
 * </ul>
 *
 * <p>값이 흩어져 있으면 "이 경로를 쓰는 곳이 어디인가" 를 물었을 때 답이 안 나온다. 여기로 모아
 * 두면 참조를 따라가는 것으로 답이 된다.</p>
 */
public final class ContainerPaths {

    /**
     * 컨테이너 안에서 <b>사용자의 앱이 사는 곳</b>. 코드 생성·프리뷰 서빙·변경 diff·저장소 push·
     * 운영 빌드가 모두 이 경로를 기준으로 동작하므로, 코드 에이전트가 여기 말고 다른 곳에 파일을
     * 만들면 그 뒤가 전부 어긋난다({@code PreviewBranchPushService#requireAppDir} 가 그 경우를
     * 원인과 함께 멈춰 세운다).
     */
    public static final String APP_DIR = "/workspace/app";

    /**
     * 앱 디렉터리에서 명령을 실행하는 문자열을 만든다. {@code "cd /workspace/app && ..."} 를 손으로
     * 잇던 자리를 대체한다 — 그 손 조립이 경로 사본이 늘어나던 주된 경로였다.
     */
    public static String inApp(String command) {
        return "cd " + APP_DIR + " && " + command;
    }

    private ContainerPaths() {
    }
}
