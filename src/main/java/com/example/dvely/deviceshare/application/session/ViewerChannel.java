package com.example.dvely.deviceshare.application.session;

/**
 * 보는 사람 한 연결. 모두 막히지 않아야 한다.
 *
 * <p>{@link #sendFrame} 은 "최신 한 장" 의미다. 앞 장을 아직 못 보냈으면 새 장으로 덮는다(설계 §4.4). 텍스트는 순서대로
 * 모두 보낸다. {@link #sendVideo} 는 H.264 라 순서대로 보내되, 밀리면 키 프레임 단위로 건너뛴다(설계 §12.3,
 * {@link VideoBacklog}).</p>
 */
public interface ViewerChannel {

    void sendText(String json);

    void sendFrame(byte[] jpeg);

    /**
     * H.264 한 장({@code [flags][Annex B]}, 그대로 보낸다). 새 연결은 키 프레임부터 받는다.
     *
     * @return 이 호출 뒤 이 사람이 키 프레임을 기다리는지. 그렇다면 세션이 기기에 키 프레임을 요청한다
     */
    boolean sendVideo(byte[] chunk, boolean keyFrame);

    void close(String reason);
}
