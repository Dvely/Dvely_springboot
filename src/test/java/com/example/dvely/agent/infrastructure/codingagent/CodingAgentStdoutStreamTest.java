package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.agent.infrastructure.codingagent.CodingAgentContainerRunner.LineSplitter;
import com.example.dvely.agent.infrastructure.codingagent.CodingAgentContainerRunner.TailBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 코딩 에이전트 stdout 을 도는 동안 줄 단위로 넘기는 부분(#600). 컨테이너 출력은 아무 데서나 끊긴 조각(frame)으로 온다.
 */
class CodingAgentStdoutStreamTest {

    private final List<String> lines = new ArrayList<>();

    @Test
    void 조각에_걸친_줄과_한글을_온전히_넘긴다() {
        LineSplitter splitter = new LineSplitter(lines::add);
        byte[] all = "{\"a\":\"저장\"}\n{\"b\":2}\n끝".getBytes(StandardCharsets.UTF_8);
        // "저" 의 3바이트 가운데를 자른다
        int cut = "{\"a\":\"".getBytes(StandardCharsets.UTF_8).length + 1;
        splitter.accept(Arrays.copyOfRange(all, 0, cut));
        splitter.accept(Arrays.copyOfRange(all, cut, all.length));
        assertThat(lines).containsExactly("{\"a\":\"저장\"}", "{\"b\":2}");

        splitter.flush();
        assertThat(lines).containsExactly("{\"a\":\"저장\"}", "{\"b\":2}", "끝");
    }

    @Test
    void 너무_긴_줄은_통째로_건너뛰고_다음_줄부터_다시_읽는다() {
        LineSplitter splitter = new LineSplitter(lines::add);
        byte[] huge = new byte[LineSplitter.MAX_LINE + 10];
        Arrays.fill(huge, (byte) 'x');
        splitter.accept("first\n".getBytes(StandardCharsets.UTF_8));
        splitter.accept(huge);
        splitter.accept("tail-of-huge\nnext\n".getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly("first", "next");
    }

    @Test
    void 받는_쪽이_던져도_다음_줄은_계속_넘긴다() {
        LineSplitter splitter = new LineSplitter(line -> {
            if (line.equals("bad")) {
                throw new IllegalStateException("boom");
            }
            lines.add(line);
        });
        splitter.accept("bad\ngood\n".getBytes(StandardCharsets.UTF_8));

        assertThat(lines).containsExactly("good");
    }

    @Test
    void 꼬리_버퍼는_마지막_바이트만_남긴다() {
        TailBuffer tail = new TailBuffer(8);
        tail.write("12345".getBytes(StandardCharsets.UTF_8), 0, 5);
        tail.write("6789".getBytes(StandardCharsets.UTF_8), 0, 4);
        assertThat(tail.toString(StandardCharsets.UTF_8)).isEqualTo("23456789");

        tail.write("abcdefghijk".getBytes(StandardCharsets.UTF_8), 0, 11);
        assertThat(tail.toString(StandardCharsets.UTF_8)).isEqualTo("defghijk");
    }
}
