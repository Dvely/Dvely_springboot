package com.example.dvely.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V66 이 쓰는 치환식이 <b>지워야 할 줄만</b> 지우는지 실제 MySQL 에서 본다 (#405).
 *
 * <h2>왜 이 테스트가 있는가</h2>
 * V66 은 대화 이력을 고치는 일회성 데이터 마이그레이션이라 "다음에 또 돌 것"이 없다. 그런데
 * 이 식에는 실제로 걸린 함정이 하나 있고, 그 함정은 <b>기록해 두지 않으면 다음 사람이 같은
 * 조건으로 같은 실수를 한다</b>.
 *
 * <p>{@code chat_messages.content} 의 콜레이션은 {@code utf8mb4_unicode_ci} — <b>대소문자를
 * 구분하지 않는다.</b> 그래서 {@code LIKE '%- preview:%'} 는 상태 표시 메시지의 이런 줄에도
 * 매치된다.</p>
 *
 * <pre>
 * 서버/서비스 상태
 * - Preview: 실행 중인 preview 없음      ← URL 도 없고 잘못된 것도 없는 정상 줄
 * </pre>
 *
 * <p>운영에서 이 행이 실제로 함께 걸렸다(message_id 79). 순진하게 지웠으면 멀쩡한 줄이
 * 사라졌을 것이다. V66 이 {@code https?://} 를 요구하는 이유가 이것이다 — URL 이 없는 줄은
 * 어떤 콜레이션에서도 매치되지 않는다.</p>
 *
 * <p>테이블에 쓰지 않는다. 식 자체를 {@code SELECT} 로 평가하므로 이력을 건드리지 않고
 * 확인만 한다.</p>
 */
@SpringBootTest
class DeadPreviewLinkStripExpressionTest {

    /** V66 과 같은 식. 여기가 V66 과 어긋나면 이 테스트는 아무것도 지키지 않는다. */
    private static final String STRIP =
            "REGEXP_REPLACE(?, '\\n- preview: https?://[^\\n]*\\n', '\\n')";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String strip(String content) {
        return jdbcTemplate.queryForObject("SELECT " + STRIP, String.class, content);
    }

    @Test
    @DisplayName("승인 메시지의 preview 주소 줄만 사라지고 주변 줄은 그대로다")
    void removesOnlyThePreviewUrlLine() {
        String before = """
                작업 결과가 preview에 준비되었습니다. 미리보기와 변경 내역을 확인해 주세요.
                승인하면 현재 preview 상태 전체가 main에 반영됩니다. 거절하면 preview에만 남습니다.
                - preview: https://qeploy.com/api/v1/previews/426b22e7-02b3-4078-843c-785ef59ab192/38f76a3719ea4acfbb9df5ddc89feda8/
                - [47] RESULT: [결과 반영] 완료했습니다.""";

        String after = strip(before);

        assertThat(after).doesNotContain("- preview:");
        // 앞뒤 줄이 붙어 버리거나 잘려 나가지 않는다.
        assertThat(after).isEqualTo("""
                작업 결과가 preview에 준비되었습니다. 미리보기와 변경 내역을 확인해 주세요.
                승인하면 현재 preview 상태 전체가 main에 반영됩니다. 거절하면 preview에만 남습니다.
                - [47] RESULT: [결과 반영] 완료했습니다.""");
        // 운영 실측과 같은 제거 길이: '- preview: '(11) + 주소(105) + 개행(1)
        assertThat(before.length() - after.length()).isEqualTo(117);
    }

    @Test
    @DisplayName("대소문자만 다른 '- Preview:' 상태 줄은 건드리지 않는다 — 운영에서 실제로 걸렸던 자리")
    void leavesTheStatusLineAlone() {
        String before = """
                서버/서비스 상태
                - 배포: 배포 이력 없음
                - Preview: 실행 중인 preview 없음
                - 클라우드 연결: 미연결""";

        assertThat(strip(before)).isEqualTo(before);
    }

    @Test
    @DisplayName("소문자여도 URL 이 없으면 건드리지 않는다 — 매치 근거는 대소문자가 아니라 URL 이다")
    void leavesALowercaseLineWithoutAUrlAlone() {
        // 콜레이션에 의존하지 않는다는 것을 보이는 짝 테스트. 위 테스트만 있으면 "대문자라서
        // 안 걸렸다" 로 읽힐 수 있고, 그러면 방어의 근거를 잘못 기억하게 된다.
        String before = "상태\n- preview: 실행 중인 preview 없음\n끝";

        assertThat(strip(before)).isEqualTo(before);
    }

    @Test
    @DisplayName("저장소 연결 승인 메시지도 같다 — 뒤따르는 저장소 이름 줄이 남는다")
    void keepsTheRepositoryNameLine() {
        String before = """
                작업물이 준비됐는데 이 프로젝트에는 아직 GitHub 저장소가 연결되어 있지 않습니다.
                - preview: https://qeploy.com/api/v1/previews/5851eaaf-3f9e-4af5-a2d0-0fc98836fa2e/b97708eda71d483b965bf06ded54ed2f/
                - 저장소 이름(기본값): hold-expiry-watch — 승인할 때 다른 이름을 보낼 수 있습니다.""";

        String after = strip(before);

        assertThat(after).doesNotContain("- preview:");
        assertThat(after).contains("- 저장소 이름(기본값): hold-expiry-watch");
    }

    @Test
    @DisplayName("주소가 메시지 끝에 있으면 남는다 — 데이터를 망치지 않는 쪽으로 실패한다")
    void aTrailingLinkIsLeftRatherThanMangled() {
        // 운영 8건은 모두 그 줄이 중간에 있어 이 경우가 없다. 그래도 적어 둔다 — 패턴이 뒤
        // 개행을 요구하므로 끝줄은 매치되지 않고 '그냥 남는다'. 잘못 자르는 것보다 낫다.
        String before = "안내\n- preview: https://qeploy.com/api/v1/previews/a/b/";

        assertThat(strip(before)).isEqualTo(before);
    }
}
