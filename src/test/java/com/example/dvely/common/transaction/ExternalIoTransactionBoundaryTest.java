package com.example.dvely.common.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.aiaccount.application.service.CodingAgentExecutionService;
import com.example.dvely.auth.application.command.AuthCommandService;
import com.example.dvely.change.application.service.ChangeService;
import com.example.dvely.deployment.application.query.DeploymentQueryService;
import com.example.dvely.domainbinding.application.command.DomainBindingCommandService;
import com.example.dvely.preview.application.service.PreviewSessionService;
import com.example.dvely.project.application.command.ProjectCommandService;
import com.example.dvely.project.application.query.ProjectQueryService;
import com.example.dvely.project.application.service.ProjectDeletionService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * 외부 I/O 를 하는 진입 메서드에 트랜잭션이 걸려 있지 않다는 것을 고정한다(#337).
 *
 * <p>왜 이런 모양인가: 이 경로들은 Docker · GitHub · Cloudflare · AWS 를 기다리므로 실제로
 * 태우려면 그 외부가 전부 필요하다. 대신 Spring 이 런타임에 실제로 쓰는 바로 그 판정기
 * ({@link AnnotationTransactionAttributeSource} — 메서드와 클래스 레벨 애너테이션을 모두 본다)
 * 에 직접 물어, 프록시가 이 메서드에 트랜잭션을 열지 <b>않는다</b>는 사실을 확인한다. 2026-09-08
 * dev 커넥션 풀 고갈의 재발 방지선이라, 누군가 편의로 {@code @Transactional} 을 다시 붙이면
 * 여기서 깨져야 한다.</p>
 *
 * <p>반대 방향도 함께 고정한다(아래 마지막 테스트) — 이 작업은 "트랜잭션을 걷어내는" 것이
 * 아니라 "외부 호출을 트랜잭션 밖으로 옮기는" 것이므로, 외부 호출이 없는 쓰기 경로는 여전히
 * 트랜잭션 안에 있어야 한다.</p>
 */
class ExternalIoTransactionBoundaryTest {

    private final AnnotationTransactionAttributeSource attributeSource =
            new AnnotationTransactionAttributeSource();

    @Test
    @DisplayName("A1 코딩 에이전트 실행 — 최대 10분짜리 CLI 실행이 트랜잭션 밖에 있다")
    void codingAgentExecutionRunsOutsideTransaction() {
        assertNoTransaction(CodingAgentExecutionService.class, "run");
    }

    @Test
    @DisplayName("A2 배포 상태·로그 조회 — GitHub Actions 호출이 트랜잭션 밖에 있다")
    void deploymentPollingRunsOutsideTransaction() {
        assertNoTransaction(DeploymentQueryService.class, "getDeploymentStatus");
        assertNoTransaction(DeploymentQueryService.class, "getDeploymentLogs");
    }

    @Test
    @DisplayName("A3 프로젝트 조회 — GitHub 을 타는 넷만 클래스 레벨 트랜잭션에서 빠져 있다")
    void githubBackedProjectQueriesSuspendTheClassLevelTransaction() {
        // 이 클래스는 클래스 레벨 @Transactional(readOnly) 라, 빠지려면 NOT_SUPPORTED 가 필요하다.
        assertPropagation(ProjectQueryService.class, "getGithubRepositories",
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertPropagation(ProjectQueryService.class, "getOverview",
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertPropagation(ProjectQueryService.class, "getCommits",
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        assertPropagation(ProjectQueryService.class, "getRepositoryHealth",
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        // 순수 DB 조회는 그대로 클래스 레벨 트랜잭션을 쓴다 — 걷어내기가 과하게 번지지 않았는지 본다.
        assertPropagation(ProjectQueryService.class, "getProjects",
                TransactionDefinition.PROPAGATION_REQUIRED);
        assertPropagation(ProjectQueryService.class, "getActivityLogs",
                TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    @DisplayName("A4 도메인 바인딩 — Cloudflare·Pages·Thread.sleep 이 트랜잭션 밖에 있다")
    void domainBindingRunsOutsideTransaction() {
        assertNoTransaction(DomainBindingCommandService.class, "bindDomain");
        assertNoTransaction(DomainBindingCommandService.class, "checkVerification");
        assertNoTransaction(DomainBindingCommandService.class, "checkVerificationAsSystem");
        assertNoTransaction(DomainBindingCommandService.class, "deleteDomain");   // 2·3 인자 오버로드 모두
        assertNoTransaction(DomainBindingCommandService.class, "cleanupProjectS3Domains");
        assertNoTransaction(DomainBindingCommandService.class, "releaseServerDomains");
    }

    @Test
    @DisplayName("A5 변경 내역 기록 — Docker exec(apk add git 포함)가 트랜잭션 밖에 있다")
    void changeRecordRunsOutsideTransaction() {
        assertNoTransaction(ChangeService.class, "record");
    }

    @Test
    @DisplayName("A6 프리뷰 세션 — 컨테이너 제거·도달 확인이 트랜잭션 밖에 있다")
    void previewSessionDockerWorkRunsOutsideTransaction() {
        assertNoTransaction(PreviewSessionService.class, "markServing");
        assertNoTransaction(PreviewSessionService.class, "closeOwned");
        assertNoTransaction(PreviewSessionService.class, "closeAllOwned");
        assertNoTransaction(PreviewSessionService.class, "cleanupExpired");
        assertNoTransaction(PreviewSessionService.class, "reclaimUnreachable");
    }

    @Test
    @DisplayName("A7 로그인·App 연동 — GitHub OAuth/User API 가 트랜잭션 밖에 있다")
    void authGithubCallsRunOutsideTransaction() {
        assertNoTransaction(AuthCommandService.class, "loginWithGithub");
        assertNoTransaction(AuthCommandService.class, "linkGithubApp");
        assertNoTransaction(AuthCommandService.class, "linkGithubAppByCode");
        assertNoTransaction(AuthCommandService.class, "refreshGithubUserToken");
    }

    @Test
    @DisplayName("A8 프로젝트 생성·연결·삭제 — GitHub·템플릿 카탈로그 호출이 트랜잭션 밖에 있다")
    void projectCommandGithubCallsRunOutsideTransaction() {
        assertNoTransaction(ProjectCommandService.class, "createProject");
        assertNoTransaction(ProjectCommandService.class, "connectRepository");
        assertNoTransaction(ProjectCommandService.class, "deleteProject");
    }

    @Test
    @DisplayName("외부 호출이 없는 쓰기 경로는 그대로 트랜잭션 안에 있다")
    void writesWithoutExternalIoStayTransactional() {
        // 삭제의 로컬 정리(대화 삭제 + 프로젝트 삭제)는 갈라지면 안 되므로 한 트랜잭션이어야 한다.
        assertPropagation(ProjectDeletionService.class, "purge",
                TransactionDefinition.PROPAGATION_REQUIRED);
        assertPropagation(ProjectCommandService.class, "disconnectRepository",
                TransactionDefinition.PROPAGATION_REQUIRED);
        assertPropagation(ProjectCommandService.class, "updateProject",
                TransactionDefinition.PROPAGATION_REQUIRED);
        assertPropagation(DomainBindingCommandService.class, "abandonVerification",
                TransactionDefinition.PROPAGATION_REQUIRED);
        assertPropagation(AuthCommandService.class, "logout",
                TransactionDefinition.PROPAGATION_REQUIRED);
    }

    private void assertNoTransaction(Class<?> type, String methodName) {
        for (Method method : publicMethods(type, methodName)) {
            assertThat(attributeSource.getTransactionAttribute(method, type))
                    .as("%s#%s 는 외부 I/O 를 기다리므로 트랜잭션을 열면 안 된다(#337)",
                            type.getSimpleName(), methodName)
                    .isNull();
        }
    }

    private void assertPropagation(Class<?> type, String methodName, int expectedPropagation) {
        for (Method method : publicMethods(type, methodName)) {
            TransactionAttribute attribute = attributeSource.getTransactionAttribute(method, type);
            assertThat(attribute)
                    .as("%s#%s 에는 트랜잭션이 있어야 한다", type.getSimpleName(), methodName)
                    .isNotNull();
            assertThat(attribute.getPropagationBehavior())
                    .as("%s#%s 의 전파 속성", type.getSimpleName(), methodName)
                    .isEqualTo(expectedPropagation);
        }
    }

    /** 오버로드가 있으면 전부 본다 — 하나만 고쳐 두고 다른 쪽에 트랜잭션이 남는 실수를 막는다. */
    private List<Method> publicMethods(Class<?> type, String methodName) {
        List<Method> methods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(methods)
                .as("%s 에 public %s 가 있어야 한다", type.getSimpleName(), methodName)
                .isNotEmpty();
        return methods;
    }
}
