package com.example.dvely.auth.application.command.dto;

public record GithubLoginCommand(String code, String state) {}
