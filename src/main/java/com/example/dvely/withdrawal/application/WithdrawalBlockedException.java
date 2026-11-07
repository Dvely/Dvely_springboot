package com.example.dvely.withdrawal.application;

import java.util.List;

/** 사용자 AWS 계정에 살아 있는 자원이 남아 탈퇴하지 않았다(#613). 아무것도 지우지 않은 상태다. */
public class WithdrawalBlockedException extends RuntimeException {

    private final transient List<LiveResource> liveResources;

    public WithdrawalBlockedException(List<LiveResource> liveResources) {
        super("AWS 계정에 아직 정리하지 않은 자원이 " + liveResources.size() + "개 있어 탈퇴할 수 없습니다.");
        this.liveResources = List.copyOf(liveResources);
    }

    public List<LiveResource> liveResources() {
        return liveResources;
    }
}
