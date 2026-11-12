package com.example.dvely.deviceshare.application.session;

/**
 * 보는 사람 한 연결. 둘 다 막히지 않아야 한다.
 *
 * <p>{@link #sendFrame} 은 "최신 한 장" 의미다. 앞 장을 아직 못 보냈으면 새 장으로 덮는다(설계 §4.4). 텍스트는 순서대로
 * 모두 보낸다.</p>
 */
public interface ViewerChannel {

    void sendText(String json);

    void sendFrame(byte[] jpeg);

    void close(String reason);
}
