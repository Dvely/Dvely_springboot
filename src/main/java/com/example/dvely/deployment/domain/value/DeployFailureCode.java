package com.example.dvely.deployment.domain.value;

/**
 * 배포가 실패로 닫힌 이유의 분류.
 *
 * errorMessage 는 사람이 읽을 상세이고 문구가 상황마다 다르다. 화면이 그 문자열을 파싱해
 * 분기하게 되면 서버가 문구를 조금만 바꿔도 조용히 깨진다. 그래서 분류를 서버가 값으로 준다.
 *
 * 옛 이력에는 이 값이 없다(null). 분류를 붙이기 전에 실패한 것들이라 되살릴 근거가 없다 —
 * 화면은 errorCode 가 없고 errorMessage 만 있는 경우를 처리해야 한다.
 */
public enum DeployFailureCode {

    /** GitHub Actions 가 실패로 끝났다. 무엇이 실패했는지는 errorMessage 의 conclusion 에 있다. */
    WORKFLOW_FAILED,

    /**
     * 결과를 확인하지 못한 채 포기했다.
     *
     * 실패했다는 뜻이 아니다. 웹훅을 놓친 이력을 회수하려던 워커가 GitHub 에서 해당 실행을
     * 찾지 못했고, 포기 시각까지 넘긴 경우다. 사이트는 실제로 떠 있을 수 있다 — 화면에서
     * "실패"로 단정해 보여주면 안 되는 분류다.
     */
    RESULT_UNKNOWN,

    /** 재시도 한도를 소진했다. 마지막 시도의 사유가 errorMessage 에 남는다. */
    RETRY_EXHAUSTED
}
