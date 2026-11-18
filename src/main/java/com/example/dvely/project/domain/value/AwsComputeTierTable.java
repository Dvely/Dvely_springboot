package com.example.dvely.project.domain.value;

import java.math.BigDecimal;

/**
 * 컴퓨트 등급이 AWS 에서 어떤 인스턴스로 뜨고, 한 달에 얼마로 추정되는지를 적은 단 하나의 표(#597).
 *
 * 서버 생성(ServerProvisioningCommandService)은 여기서 인스턴스 유형을 읽고, 비용 추정(InfrastructureCostEstimator)의
 * AWS SERVER 행은 여기서 월 금액을 읽는다. 두 곳이 따로 숫자를 들고 있으면 "LARGE 로 추정했는데 micro 로 떴다"가 다시 생긴다.
 *
 * 등급만 본다. 실제 생성 경로는 배포 형태(NATIVE·DOCKER)와 상관없이 EC2 하나뿐이라 아키텍처 설정으로 갈라질 것이 없다.
 * ARM 인스턴스(#580)를 들이면 이 표에 열을 더한다.
 *
 * 월 금액은 정적 근사치다. 실제 가격은 사용자가 연결한 계정의 리전마다 다르고, 리전별 가격은 따로 다룰 과제다.
 * 숫자를 바꾸면 InfrastructureCostEstimator.PRICE_TABLE_VERSION 도 올린다.
 */
public final class AwsComputeTierTable {

    /** 등급 하나의 AWS 사양 — 띄울 인스턴스 유형과 그 유형의 월 추정 금액(USD, 730시간). */
    public record Spec(String instanceType, BigDecimal monthlyUsd) {
    }

    private AwsComputeTierTable() {
    }

    public static Spec of(ComputeTier tier) {
        // switch 식이라 등급이 하나 늘면 여기서 컴파일이 깨진다 — 표에 빠진 등급이 조용히 생기지 않는다.
        return switch (tier) {
            case MICRO -> new Spec("t3.micro", new BigDecimal("8.50"));
            case SMALL -> new Spec("t3.small", new BigDecimal("17.00"));
            case MEDIUM -> new Spec("t3.medium", new BigDecimal("34.00"));
            case LARGE -> new Spec("t3.large", new BigDecimal("68.00"));
        };
    }

    /** 프로젝트에 인프라 설정이 없을 때 띄우는 유형. 가장 작은 등급과 같다(프리티어 대상). */
    public static String fallbackInstanceType() {
        return of(ComputeTier.MICRO).instanceType();
    }
}
