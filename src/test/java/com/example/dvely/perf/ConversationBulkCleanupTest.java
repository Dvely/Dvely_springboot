package com.example.dvely.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.chat.application.command.ChatCommandService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * U6(#341) 6-8 의 위험한 부분만 실 DB 로 못박는다.
 *
 * <p>대화 일괄 처리를 "엔티티 N 건 로드 → 한 건씩 save/deleteById" 에서 벌크 한 문장으로 바꿨다.
 * 벌크 문장은 JPA 의 생애주기 콜백을 타지 않으므로, 예전과 같게 남아야 하는 두 가지가 실제로
 * 같은지는 DB 에 물어봐야만 알 수 있다:</p>
 * <ol>
 *   <li><b>updated_at</b> — @UpdateTimestamp 는 벌크 UPDATE 에서 돌지 않는다. 같게 유지되는 근거는
 *       {@code chat_sessions.updated_at} 컬럼의 {@code ON UPDATE CURRENT_TIMESTAMP} 뿐이다. 휴지통
 *       목록이 updated_at 순이라 이게 깨지면 순서가 달라진다.</li>
 *   <li><b>연관 삭제</b> — 메시지는 chat_messages 의 {@code ON DELETE CASCADE}(V19)가, 승인 등
 *       이력은 {@code ON DELETE SET NULL} 이 처리한다. 벌크 DELETE 도 실제 SQL DELETE 이므로 그대로
 *       돌아야 한다.</li>
 * </ol>
 */
@SpringBootTest
class ConversationBulkCleanupTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ChatCommandService chatCommandService;

    @Test
    void trashingAProjectsConversationsMarksThemAndStillRefreshesUpdatedAt() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        Long first = seedConversation(userId, projectId);
        Long second = seedConversation(userId, projectId);
        // 이미 휴지통에 있는 대화는 건드리지 않아야 한다(예전 softDelete 의 "이미 삭제됐으면 무시").
        Long alreadyTrashed = seedConversation(userId, projectId);
        LocalDateTime originalDeletedAt = LocalDateTime.now().minusDays(3).withNano(0);
        jdbc.update("update chat_sessions set is_deleted = 1, deleted_at = ?, updated_at = ?"
                        + " where chat_session_id = ?",
                originalDeletedAt, originalDeletedAt, alreadyTrashed);
        jdbc.update("update chat_sessions set updated_at = ? where chat_session_id in (?, ?)",
                LocalDateTime.now().minusDays(2), first, second);

        chatCommandService.trashConversationsForProject(userId, projectId);

        assertThat(deletedFlag(first)).isTrue();
        assertThat(deletedFlag(second)).isTrue();
        assertThat(deletedAt(first)).isNotNull();
        // 같은 문장이므로 두 행의 deleted_at 이 동일하다 — 예전 루프도 같은 타임스탬프를 썼다.
        assertThat(deletedAt(first)).isEqualTo(deletedAt(second));
        // ON UPDATE CURRENT_TIMESTAMP 가 살아 있는지 — 휴지통 목록 정렬이 여기에 달려 있다.
        assertThat(updatedAt(first)).isAfter(LocalDateTime.now().minusMinutes(1));
        // 이미 휴지통에 있던 대화의 deleted_at 은 덮이지 않는다.
        assertThat(deletedAt(alreadyTrashed)).isEqualTo(originalDeletedAt);
        assertThat(updatedAt(alreadyTrashed)).isEqualTo(originalDeletedAt);
    }

    @Test
    void deletingAProjectsConversationsCascadesMessagesAndNullsOutApprovalReferences() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        Long conversationId = seedConversation(userId, projectId);
        seedMessage(conversationId);
        seedMessage(conversationId);
        Long approvalId = seedApproval(userId, projectId, conversationId);

        chatCommandService.deleteConversationsForProject(userId, projectId);

        assertThat(count("select count(*) from chat_sessions where chat_session_id = ?", conversationId))
                .isZero();
        assertThat(count("select count(*) from chat_messages where chat_session_id = ?", conversationId))
                .as("메시지는 chat_messages 의 ON DELETE CASCADE 가 지운다 — 벌크 DELETE 에서도 같다")
                .isZero();
        assertThat(count("select count(*) from approvals where approval_id = ?", approvalId))
                .as("승인 이력은 보존된다 — FK 가 ON DELETE SET NULL 이다")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select chat_session_id from approvals where approval_id = ?", Long.class, approvalId))
                .isNull();
    }

    @Test
    void purgingExpiredTrashDeletesOnlyRowsPastTheRetentionCutoff() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        Long expired = seedConversation(userId, projectId);
        Long stillWithinRetention = seedConversation(userId, projectId);
        jdbc.update("update chat_sessions set is_deleted = 1, deleted_at = ? where chat_session_id = ?",
                LocalDateTime.now().minusDays(8), expired);
        jdbc.update("update chat_sessions set is_deleted = 1, deleted_at = ? where chat_session_id = ?",
                LocalDateTime.now().minusDays(1), stillWithinRetention);

        int purged = chatCommandService.purgeExpiredConversations();

        assertThat(purged).as("돌려주는 값은 지운 행 수다").isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from chat_sessions where chat_session_id = ?", expired)).isZero();
        assertThat(count("select count(*) from chat_sessions where chat_session_id = ?",
                stillWithinRetention)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 시딩·조회 헬퍼

    private Long seedUser() {
        return userRepository.save(
                new User(new GithubId("u6-bulk-" + System.nanoTime()), "octo", null)).getId();
    }

    private Long seedProject(Long userId) {
        return projectRepository.save(new Project(
                userId, "u6-bulk", "scratch", null, "fast", RepositoryVisibility.PUBLIC)).getId();
    }

    private Long seedConversation(Long userId, Long projectId) {
        jdbc.update("insert into chat_sessions (user_id, project_id, title) values (?, ?, ?)",
                userId, projectId, "u6-bulk");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void seedMessage(Long conversationId) {
        jdbc.update("insert into chat_messages (chat_session_id, role, content) values (?, 'user', ?)",
                conversationId, "안녕");
    }

    private Long seedApproval(Long userId, Long projectId, Long conversationId) {
        jdbc.update("""
                insert into approvals
                    (user_id, project_id, chat_session_id, approval_type, status, summary)
                values (?, ?, ?, 'CHANGE', 'PENDING', '요약')
                """, userId, projectId, conversationId);
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private boolean deletedFlag(Long conversationId) {
        return jdbc.queryForObject(
                "select is_deleted from chat_sessions where chat_session_id = ?", Boolean.class, conversationId);
    }

    private LocalDateTime deletedAt(Long conversationId) {
        return jdbc.queryForObject(
                "select deleted_at from chat_sessions where chat_session_id = ?",
                LocalDateTime.class, conversationId);
    }

    private LocalDateTime updatedAt(Long conversationId) {
        return jdbc.queryForObject(
                "select updated_at from chat_sessions where chat_session_id = ?",
                LocalDateTime.class, conversationId);
    }
}
