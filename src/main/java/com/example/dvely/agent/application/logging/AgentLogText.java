package com.example.dvely.agent.application.logging;

import java.util.Map;
import java.util.TreeSet;

/**
 * 에이전트 실행 로그에 남길 문자열을 줄인다(#617).
 *
 * <p>툴 호출 로그는 {@code input} 을 통째로 INFO 로 남겼다. {@code write_file} 의 input 은 쓰려는 파일 내용
 * 전부라서, 모델이 {@code .env} 를 쓰면 그 비밀값이 서버 로그에 그대로 남았다. 사용자 지시문(instruction)도
 * 길이 제한 없이 INFO 로 남았다. 로그는 "무엇을 했는가" 를 알면 충분하다. 내용은 모델에게 가는 대화에만
 * 남기고 로그에는 경로·길이·앞부분만 남긴다.</p>
 *
 * <p>모델에게 돌려주는 tool 결과에는 쓰지 않는다. 이 클래스는 로그와 실패 요약(사용자에게 보이는 로그
 * 발췌)용이다.</p>
 */
public final class AgentLogText {

    /** 사용자 지시문을 로그에 남길 때의 최대 글자 수. */
    public static final int INSTRUCTION_MAX_CHARS = 500;
    /** 셸 명령과 tool 결과를 로그에 남길 때의 최대 글자 수. */
    public static final int COMMAND_MAX_CHARS = 300;
    /** 파일 경로를 로그에 남길 때의 최대 글자 수. 경로는 모델이 정하므로 길이를 믿지 않는다. */
    public static final int PATH_MAX_CHARS = 200;

    private AgentLogText() {
    }

    /**
     * {@code max} 글자를 넘으면 앞부분만 남기고 원래 길이를 덧붙인다. {@code null} 은 빈 문자열이다.
     * 잘리는 자리가 서로게이트 쌍 가운데면 한 글자 앞에서 자른다(깨진 문자를 로그에 남기지 않는다).
     */
    public static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…(총 " + text.length() + "자)";
    }

    /** 사용자 지시문: 전체 길이와 앞 {@value #INSTRUCTION_MAX_CHARS}자. */
    public static String instruction(String instruction) {
        String text = instruction == null ? "" : instruction;
        return "(" + text.length() + "자) " + clip(text, INSTRUCTION_MAX_CHARS);
    }

    /**
     * tool 호출 인자 요약. 파일 내용은 길이만 남긴다.
     *
     * <ul>
     *   <li>{@code write_file} — 경로와 내용 길이</li>
     *   <li>{@code execute_command} — 명령 앞 {@value #COMMAND_MAX_CHARS}자</li>
     *   <li>{@code read_file} — 경로</li>
     *   <li>그 밖의 tool — 인자 이름만(값이 무엇인지 모르므로 남기지 않는다)</li>
     * </ul>
     */
    public static String toolInput(String toolName, Map<String, Object> input) {
        return toolInput(toolName, input, COMMAND_MAX_CHARS, PATH_MAX_CHARS);
    }

    /**
     * {@link #toolInput(String, Map)} 와 같고, 명령·경로의 상한만 호출자가 정한다. 요약을 다시 자르면
     * "(총 N자)" 가 원래 길이가 아니라 요약의 길이를 가리키게 되므로, 더 짧게 남겨야 하는 곳은 이걸 쓴다.
     */
    public static String toolInput(String toolName, Map<String, Object> input, int commandMaxChars, int pathMaxChars) {
        Map<String, Object> args = input == null ? Map.of() : input;
        return switch (toolName == null ? "" : toolName) {
            case "write_file" -> "path=" + path(args.get("path"), pathMaxChars)
                    + " content=" + contentLength(args.get("content"));
            case "execute_command" -> "command=" + clip(stringOrEmpty(args.get("command")), commandMaxChars);
            case "read_file" -> "path=" + path(args.get("path"), pathMaxChars);
            default -> "args=" + new TreeSet<>(args.keySet());
        };
    }

    /**
     * tool 결과 요약. {@code read_file} 의 결과는 파일 내용 그 자체라 길이만 남긴다. 나머지는 앞
     * {@value #COMMAND_MAX_CHARS}자다.
     */
    public static String toolResult(String toolName, String result) {
        String text = result == null ? "" : result;
        if ("read_file".equals(toolName)) {
            return "(" + text.length() + "자)";
        }
        return clip(text, COMMAND_MAX_CHARS);
    }

    private static String path(Object value, int max) {
        return value instanceof String text ? clip(text, max) : "(없음)";
    }

    private static String contentLength(Object value) {
        return value instanceof String text ? text.length() + "자" : "(없음)";
    }

    private static String stringOrEmpty(Object value) {
        return value instanceof String text ? text : "";
    }
}
