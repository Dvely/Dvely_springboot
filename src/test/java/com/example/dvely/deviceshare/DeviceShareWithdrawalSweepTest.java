package com.example.dvely.deviceshare;

import static com.example.dvely.support.JdbcRows.insert;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deviceshare.application.service.CreatedDeviceShare;
import com.example.dvely.deviceshare.application.service.DeviceShareService;
import com.example.dvely.deviceshare.application.session.ShareSessionRegistry;
import com.example.dvely.deviceshare.application.session.ViewerChannel;
import com.example.dvely.deviceshare.domain.value.DeviceShareKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 탈퇴로 링크 행이 함께 지워지면(ON DELETE CASCADE) 연결된 공유도 끊긴다(#652). */
@SpringBootTest
class DeviceShareWithdrawalSweepTest {

    @Autowired private DeviceShareService service;
    @Autowired private ShareSessionRegistry sessions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 사용자_행이_지워지면_점검이_공유를_끝낸다() {
        long userId = insert(jdbc, "insert into users (github_user_id, user_name) values (?, ?)",
                "ds652s-" + System.nanoTime(), "ds652s");
        CreatedDeviceShare created = service.create(userId, DeviceShareKind.WEB, "http://localhost:5173", 30);
        RecordingViewer viewer = new RecordingViewer();
        assertThat(sessions.join(created.link(), viewer, "a", false)).isPresent();

        service.endSessionsWithoutActiveLink();
        assertThat(viewer.closed).isNull();

        jdbc.update("delete from users where user_id = ?", userId);
        assertThat(jdbc.queryForObject("select count(*) from device_share_links where share_id = ?",
                Integer.class, created.link().getId())).isZero();

        service.endSessionsWithoutActiveLink();
        assertThat(viewer.closed).isEqualTo("revoked");
        assertThat(viewer.texts).anyMatch(t -> t.contains("\"ended\""));
    }

    private static final class RecordingViewer implements ViewerChannel {
        final List<String> texts = new ArrayList<>();
        String closed;

        @Override
        public void sendText(String json) {
            texts.add(json);
        }

        @Override
        public void sendFrame(byte[] jpeg) {
        }

        @Override
        public void close(String reason) {
            closed = reason;
        }
    }
}
