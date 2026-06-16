package com.example.dvely.agent.application.service;

import org.springframework.stereotype.Service;

@Service
public class BuildFailureAnalyzer {

    private static final int MAX_LOG_LENGTH = 4000;

    public Analysis analyze(String rawLog) {
        String normalized = rawLog == null ? "" : rawLog.trim();
        String lower = normalized.toLowerCase();

        if (lower.contains("cannot find module") || lower.contains("module not found")) {
            return analysis(
                    "빌드에 필요한 모듈을 찾지 못했습니다.",
                    normalized,
                    "누락된 dependency와 import 경로를 확인하고 다시 설치한 뒤 build를 실행합니다."
            );
        }
        if (lower.contains("typescript") || lower.contains("type error") || lower.contains("tsc")) {
            return analysis(
                    "타입 검사에서 오류가 발견되어 빌드가 중단되었습니다.",
                    normalized,
                    "로그에 표시된 파일의 타입 선언과 함수 인자를 수정한 뒤 build를 다시 실행합니다."
            );
        }
        if (lower.contains("syntaxerror") || lower.contains("unexpected token") || lower.contains("parse error")) {
            return analysis(
                    "소스 코드 문법 오류로 빌드가 중단되었습니다.",
                    normalized,
                    "첫 번째 문법 오류가 발생한 파일과 줄을 수정하고 연쇄 오류가 사라지는지 확인합니다."
            );
        }
        if (lower.contains("out of memory") || lower.contains("heap")) {
            return analysis(
                    "빌드 과정에서 사용할 수 있는 메모리가 부족했습니다.",
                    normalized,
                    "불필요한 build 작업을 줄이고 dependency를 정리한 뒤 메모리 사용량을 낮춰 다시 실행합니다."
            );
        }
        return analysis(
                "프로젝트 빌드가 완료되지 않았습니다.",
                normalized,
                "로그의 첫 번째 실제 오류를 기준으로 관련 파일을 수정한 뒤 dependency 설치와 build를 다시 실행합니다."
        );
    }

    private Analysis analysis(String message, String log, String fix) {
        return new Analysis(message, tail(log), fix);
    }

    private String tail(String value) {
        if (value == null || value.isBlank()) {
            return "빌드 로그를 수집하지 못했습니다.";
        }
        return value.length() <= MAX_LOG_LENGTH
                ? value
                : value.substring(value.length() - MAX_LOG_LENGTH);
    }

    public record Analysis(
            String userMessage,
            String logExcerpt,
            String suggestedFix
    ) {
    }
}
