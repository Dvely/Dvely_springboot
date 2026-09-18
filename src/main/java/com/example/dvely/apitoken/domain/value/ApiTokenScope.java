package com.example.dvely.apitoken.domain.value;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a personal access token may do.
 *
 * <p>Deliberately only two levels. Fine-grained per-resource scopes are the kind of thing that
 * looks thorough and then goes stale the moment an endpoint is added — a token minted with
 * yesterday's scope list silently loses access, or silently gains it. Two levels stay correct as
 * the API grows, and the real narrowing happens where it belongs: the agent-facing tool set simply
 * does not expose the irreversible operations (project/server deletion, approvals, budget changes).
 * This enum is the second line, not the first.</p>
 */
@Schema(description = "토큰 권한. READ = 조회만, WRITE = 조회 + 변경")
public enum ApiTokenScope {

    READ,
    WRITE;

    /** WRITE implies READ; READ never implies WRITE. */
    public boolean allowsWrite() {
        return this == WRITE;
    }
}
