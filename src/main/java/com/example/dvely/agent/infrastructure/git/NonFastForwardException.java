package com.example.dvely.agent.infrastructure.git;

/** push 하는 사이 원격 브랜치가 움직였다. 호출자가 base 를 다시 받아 재시도한다. */
public class NonFastForwardException extends ServerGitException {

    public NonFastForwardException(String branch) {
        super("원격 브랜치가 그 사이 바뀌어 push 가 거부됐습니다: " + branch, null);
    }
}
