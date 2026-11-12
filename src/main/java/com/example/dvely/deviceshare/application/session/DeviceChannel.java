package com.example.dvely.deviceshare.application.session;

import java.nio.ByteBuffer;

/** 기기 터널 한 연결. 보내기는 막히지 않아야 한다(구현이 버퍼링한다). */
public interface DeviceChannel {

    void sendBinary(ByteBuffer frame);

    void close(String reason);
}
