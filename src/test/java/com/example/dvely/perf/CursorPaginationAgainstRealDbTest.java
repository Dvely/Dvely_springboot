package com.example.dvely.perf;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.auth.domain.model.User;
import com.example.dvely.auth.domain.repository.UserRepository;
import com.example.dvely.auth.domain.value.GithubId;
import com.example.dvely.chat.application.query.ChatQueryService;
import com.example.dvely.domainbinding.application.query.DomainBindingQueryService;
import com.example.dvely.project.domain.model.Project;
import com.example.dvely.project.domain.repository.ProjectRepository;
import com.example.dvely.project.domain.value.RepositoryVisibility;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * U6(#341) 6-3·6-4 의 커서 쿼리를 실 MySQL 로 한 번 돌린다.
 *
 * <p>이유는 하나다. 커서 쿼리는 {@code (:after is null or x.id > :after)} 꼴인데, 이 "파라미터가
 * null 인지 SQL 에서 묻는" 형태는 Hibernate 가 바인딩 타입을 정하지 못해 <b>실행 시점에만</b> 깨질 수
 * 있다. Spring Data 의 부팅 시 JPQL 검증은 문법만 보므로 이건 못 잡는다. 그래서 커서를 안 준 첫
 * 페이지와 커서를 준 다음 페이지를 각각 실제로 실행해 본다.</p>
 *
 * <p>페이지를 이어 받았을 때 <b>행이 빠지거나 겹치지 않는지</b>도 여기서 확인한다 — 커서
 * 페이지네이션에서 제일 흔한 버그이고, 정렬에 id tiebreaker 를 붙인 것이 그 때문이다.</p>
 */
@SpringBootTest
class CursorPaginationAgainstRealDbTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ChatQueryService chatQueryService;
    @Autowired private DomainBindingQueryService domainBindingQueryService;

    @Test
    void messagePagesWalkForwardWithoutSkippingOrRepeatingARow() {
        Long userId = seedUser();
        Long conversationId = seedConversation(userId, seedProject(userId));
        // created_at 을 전부 같은 초로 심는다 — id tiebreaker 가 없으면 커서가 깨지는 상황이다.
        for (int i = 0; i < 5; i++) {
            jdbc.update("insert into chat_messages (chat_session_id, role, content, created_at)"
                    + " values (?, 'user', ?, ?)", conversationId, "메시지 " + i, LocalDateTime.now());
        }

        var first = chatQueryService.getMessages(userId, conversationId, 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        var second = chatQueryService.getMessages(userId, conversationId, 2, first.nextCursor());
        var third = chatQueryService.getMessages(userId, conversationId, 2, second.nextCursor());

        assertThat(second.items()).hasSize(2);
        assertThat(third.items()).hasSize(1);
        assertThat(third.nextCursor()).as("마지막 페이지에는 커서가 없다").isNull();

        List<Long> walked = java.util.stream.Stream.of(first, second, third)
                .flatMap(page -> page.items().stream())
                .map(message -> message.messageId())
                .toList();
        assertThat(walked).doesNotHaveDuplicates().hasSize(5).isSorted();
        assertThat(walked).isEqualTo(chatQueryService.getMessages(userId, conversationId).stream()
                .map(message -> message.messageId()).toList());
    }

    @Test
    void domainPagesWalkFromNewestToOldestWithoutSkippingOrRepeatingARow() {
        Long userId = seedUser();
        Long projectId = seedProject(userId);
        for (int i = 0; i < 3; i++) {
            seedDomain(projectId, "d" + i + "-" + System.nanoTime() + ".example.com");
        }

        var first = domainBindingQueryService.getProjectDomains(userId, projectId, 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        var second = domainBindingQueryService.getProjectDomains(userId, projectId, 2, first.nextCursor());
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isNull();

        List<Long> walked = java.util.stream.Stream.of(first, second)
                .flatMap(page -> page.items().stream())
                .map(domain -> domain.domainId())
                .toList();
        assertThat(walked).doesNotHaveDuplicates().hasSize(3);
        assertThat(walked).isEqualTo(domainBindingQueryService.getProjectDomains(userId, projectId).stream()
                .map(domain -> domain.domainId()).toList());
    }

    private Long seedUser() {
        return userRepository.save(
                new User(new GithubId("u6-cursor-" + System.nanoTime()), "octo", null)).getId();
    }

    private Long seedProject(Long userId) {
        return projectRepository.save(new Project(
                userId, "u6-cursor", "scratch", null, "fast", RepositoryVisibility.PUBLIC)).getId();
    }

    private Long seedConversation(Long userId, Long projectId) {
        jdbc.update("insert into chat_sessions (user_id, project_id, title) values (?, ?, ?)",
                userId, projectId, "u6-cursor");
        return jdbc.queryForObject("select last_insert_id()", Long.class);
    }

    private void seedDomain(Long projectId, String hostname) {
        jdbc.update("""
                insert into domains
                    (project_id, domain_type, hosting_target, domain_name, status, verification_method,
                     https_enforced, certificate_status, created_at)
                values (?, 'MANAGED_SUBDOMAIN', 'GITHUB_PAGES', ?, 'CONNECTED', 'CNAME', 1, 'ACTIVE', ?)
                """, projectId, hostname, LocalDateTime.now());
    }
}
