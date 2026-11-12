package com.example.dvely.deviceshare.infrastructure.websocket;

import com.example.dvely.deviceshare.application.session.ViewerChannel;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 보는 사람 연결. 가상 스레드 하나가 실제 전송을 맡고, 호출하는 쪽은 막히지 않는다.
 *
 * <p>텍스트는 순서대로 모두, 화면은 "최신 한 장" 만 보낸다. 느린 사람은 장이 빠질 뿐 다른 사람을 붙잡지 않는다
 * (설계 §4.4). 텍스트가 256개 넘게 밀리면 따라오지 못하는 연결로 보고 닫는다.</p>
 */
@Slf4j
class WebSocketViewerChannel implements ViewerChannel {

    private static final int MAX_QUEUED_TEXTS = 256;

    private final WebSocketSession session;
    private final Deque<String> texts = new ArrayDeque<>();
    private byte[] frameSlot;
    private boolean pingDue;
    private boolean closed;
    private String closeReason;

    WebSocketViewerChannel(WebSocketSession session) {
        this.session = session;
        Thread.ofVirtual().name("device-share-viewer-" + session.getId()).start(this::pump);
    }

    @Override
    public synchronized void sendText(String json) {
        if (closed) {
            return;
        }
        if (texts.size() >= MAX_QUEUED_TEXTS) {
            closeLocked("slow-consumer");
            return;
        }
        texts.addLast(json);
        notifyAll();
    }

    @Override
    public synchronized void sendFrame(byte[] jpeg) {
        if (closed) {
            return;
        }
        frameSlot = jpeg;
        notifyAll();
    }

    @Override
    public synchronized void close(String reason) {
        closeLocked(reason);
    }

    synchronized void ping() {
        if (!closed) {
            pingDue = true;
            notifyAll();
        }
    }

    private void closeLocked(String reason) {
        if (!closed) {
            closed = true;
            closeReason = reason;
            notifyAll();
        }
    }

    private void pump() {
        try {
            while (true) {
                String text;
                byte[] frame;
                boolean ping;
                synchronized (this) {
                    while (!closed && texts.isEmpty() && frameSlot == null && !pingDue) {
                        wait();
                    }
                    text = texts.pollFirst();
                    frame = text == null ? frameSlot : null;
                    if (frame != null) {
                        frameSlot = null;
                    }
                    ping = text == null && frame == null && pingDue;
                    pingDue = pingDue && !ping;
                    if (closed && text == null) {
                        break; // 남은 텍스트(종료 안내)는 보낸 뒤 닫는다
                    }
                }
                if (!session.isOpen()) {
                    break;
                }
                if (text != null) {
                    session.sendMessage(new TextMessage(text));
                } else if (frame != null) {
                    session.sendMessage(new BinaryMessage(frame));
                } else if (ping) {
                    session.sendMessage(new PingMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("[DeviceShare] 보는 사람에게 보내지 못함: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.NORMAL.withReason(closeReason == null ? "closed" : closeReason));
                }
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }
    }
}
