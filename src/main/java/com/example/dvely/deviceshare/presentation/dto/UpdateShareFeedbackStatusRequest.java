package com.example.dvely.deviceshare.presentation.dto;

import com.example.dvely.deviceshare.domain.value.FeedbackStatus;
import jakarta.validation.constraints.NotNull;

/** 주인의 받기(ACCEPTED)·무시(DISMISSED)·확인(CONFIRMED). 고침은 {@code POST …/fixed}. */
public record UpdateShareFeedbackStatusRequest(@NotNull FeedbackStatus status) {
}
