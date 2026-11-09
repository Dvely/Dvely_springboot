package com.example.dvely.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 테스트 코드는 방금 넣은 행의 키를 {@code last_insert_id()} 를 따로 불러 읽지 않는다. 키는 {@link JdbcRows#insert} 로
 * 받는다. 따로 부르면 풀이 다른 커넥션을 줄 수 있어 간헐적으로 틀린 키를 받는다(이유는 {@link JdbcRows#insert} 주석).
 *
 * <p>이 패턴은 한 테스트에서 다른 테스트로 복사되며 다섯 곳까지 퍼졌다. 그중 하나가 CI 에서 간헐 실패했다.</p>
 */
class LastInsertIdGuardTest {

    /** 문자열이나 텍스트 블록 안의 SQL 로 쓰인 경우만 본다. 주석에서 이름을 말하는 것은 괜찮다. */
    private static final Pattern READ_IN_SQL = Pattern.compile("\"\\s*select\\s+last_insert_id\\s*\\(", Pattern.CASE_INSENSITIVE);

    @Test
    void noTestReadsTheKeyOfItsInsertInASeparateCall() throws IOException {
        List<Path> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                scanned++;
                if (READ_IN_SQL.matcher(Files.readString(file)).find()) {
                    offenders.add(file);
                }
            }
        }

        assertThat(scanned).as("테스트 소스를 실제로 읽었다").isGreaterThan(100);
        assertThat(offenders).as("INSERT 의 키는 JdbcRows.insert 로 받는다").isEmpty();
    }
}
