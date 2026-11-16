package com.example.dvely.deviceshare.presentation.dto;

import jakarta.validation.constraints.Size;

/** @param note 무엇을 고쳤는지 한 줄(300자). 짚은 사람의 뷰어에 보인다 */
public record MarkShareFeedbackFixedRequest(@Size(max = 300) String note) {
}
