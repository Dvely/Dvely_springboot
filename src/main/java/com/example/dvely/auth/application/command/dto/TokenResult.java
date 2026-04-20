package com.example.dvely.auth.application.command.dto;

/**
 * @param accessToken        클라이언트에게 발급하는 서비스 JWT (1시간)
 * @param refreshToken       액세스 토큰 갱신용 토큰 (30일)
 * @param githubAppInstalled GitHub App 설치 여부 (false면 설치 페이지로 유도)
 */
public record TokenResult(String accessToken, String refreshToken, boolean githubAppInstalled) {}
