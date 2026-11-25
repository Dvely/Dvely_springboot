package com.example.dvely.chat.infrastructure.persistence.repository;

import com.example.dvely.chat.domain.model.ChatMessagePin;
import com.example.dvely.chat.domain.model.ChatMessagePinLabel;
import com.example.dvely.chat.domain.repository.ChatMessagePinRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC 로 둔다. 키가 (메시지, 번호) 둘이고, 목록 조회는 본문(MEDIUMTEXT)을 빼고 칩만 읽어야 해서다 — 엔티티로 두면
 * 메시지 목록 한 쪽마다 짚기 기술 전체가 따라온다.
 */
@Repository
@RequiredArgsConstructor
public class ChatMessagePinRepositoryAdapter implements ChatMessagePinRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    @Override
    public void saveAll(Long messageId, List<ChatMessagePin> pins) {
        if (pins.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "insert into chat_message_pins (message_id, position, label, element_json) values (?, ?, ?, ?)",
                pins, pins.size(), (ps, pin) -> {
                    ps.setLong(1, messageId);
                    ps.setInt(2, pin.position());
                    ps.setString(3, pin.label());
                    ps.setString(4, pin.elementJson());
                });
    }

    @Override
    public List<ChatMessagePin> findByMessageId(Long messageId) {
        return jdbc.query(
                "select position, label, element_json from chat_message_pins where message_id = ? order by position",
                (rs, n) -> new ChatMessagePin(rs.getInt("position"), rs.getString("label"), rs.getString("element_json")),
                messageId);
    }

    @Override
    public Map<Long, List<ChatMessagePinLabel>> findLabelsByMessageIds(Collection<Long> messageIds) {
        Map<Long, List<ChatMessagePinLabel>> out = new LinkedHashMap<>();
        if (messageIds.isEmpty()) {
            return out;
        }
        named.query("""
                        select message_id, position, label from chat_message_pins
                         where message_id in (:ids) order by message_id, position
                        """,
                new MapSqlParameterSource("ids", messageIds),
                rs -> {
                    out.computeIfAbsent(rs.getLong("message_id"), id -> new ArrayList<>())
                            .add(new ChatMessagePinLabel(rs.getInt("position"), rs.getString("label")));
                });
        return out;
    }

    @Override
    public List<ChatMessagePin> findByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        List<Long> owners = jdbc.queryForList("""
                select p.message_id from chat_message_pins p
                  join chat_messages m on m.message_id = p.message_id
                 where m.task_id = ? and m.role = 'user'
                 order by p.message_id limit 1
                """, Long.class, taskId);
        return owners.isEmpty() ? List.of() : findByMessageId(owners.get(0));
    }
}
