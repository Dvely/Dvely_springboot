package com.example.dvely.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V62 (이슈 #338 3-2) 가드 — 죽은 테이블·컬럼이 다시 살아나지 않게 한다.
 *
 * <p>{@code ddl-auto: validate} 는 "엔티티가 요구하는 것이 스키마에 있는가"만 보고, 반대로
 * "스키마에 아무도 안 쓰는 것이 남아 있는가"는 보지 않는다. 즉 누가 실수로 이 구조를 되살리는
 * 마이그레이션을 넣어도 다른 테스트는 전부 통과한다.</p>
 */
@SpringBootTest
class DeadSchemaRemovalTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 매핑되지_않던_죽은_테이블이_사라졌다() {
        // V1 이후 한 번도 @Table 로 매핑된 적이 없다. 배포 이력의 정본은 deployment_histories 다.
        // 남아 있는 동안에는 projects 를 참조하는 FK 때문에 프로젝트 삭제마다 참조 검사 비용만 냈다.
        Integer count = jdbcTemplate.queryForObject(
                """
                        select count(*) from information_schema.tables
                        where table_schema = database() and table_name in ('pipelines', 'deployments')
                        """,
                Integer.class);

        assertThat(count).isZero();
    }

    @Test
    void 평문_토큰을_담던_컬럼이_사라졌다() {
        // users.access_token 은 평문 GitHub 토큰이 들어가던 컬럼이다. 지금 쓰는 토큰은 V12 의
        // github_user_access_token 이고 AesEncryptor 로 암호화된다 — 이 셋은 UserEntity 가
        // 매핑하지도 않는 보안 부채였다.
        List<String> remaining = jdbcTemplate.queryForList(
                """
                        select column_name from information_schema.columns
                        where table_schema = database() and table_name = 'users'
                          and column_name in ('access_token', 'scope', 'token_expires_at')
                        """,
                String.class);

        assertThat(remaining).isEmpty();
    }
}
