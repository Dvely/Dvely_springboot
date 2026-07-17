package com.example.dvely.environment.application.port.in;

import com.example.dvely.environment.domain.value.EnvironmentScope;
import java.util.Map;

/**
 * Internal-only seam for future integration (U3 design §6): provides decrypted plaintext
 * environment variables for a project/scope so preview containers and deployment workflows can
 * be given them. <b>Never call this from an HTTP-facing controller</b> —
 * {@code EnvironmentVariableController} does not, and must not, use it. Every other public
 * entry point in this domain masks secret values (see EnvironmentVariableResult); this is the
 * one exception, deliberately kept out of the presentation layer entirely.
 *
 * <p>There is no {@code ownerUserId} parameter: the intended callers are other internal domains
 * (agent preview container setup, deployment workflow dispatch) that have already resolved and
 * verified project ownership through their own flow before reaching here. Do not add an
 * HTTP-facing caller without re-adding an ownership check at that new call site.</p>
 *
 * <p>Not wired to any caller yet (out of scope for U3, see design §9) — future consumers:</p>
 * <ul>
 *   <li>Preview: {@code agent} domain injects {@link EnvironmentScope#PREVIEW} into the Docker
 *       preview container via {@code DockerContainerService}.</li>
 *   <li>Deployment: {@code deployment} domain delivers {@link EnvironmentScope#PRODUCTION} to the
 *       GitHub Actions workflow. The transport mechanism is an open question for that unit —
 *       a workflow_dispatch input would put plaintext secrets in run logs/API responses, so
 *       syncing to GitHub Actions Secrets first (rather than passing values inline) needs to be
 *       evaluated there.</li>
 * </ul>
 */
public interface EnvironmentValueResolver {

    /**
     * @return key to plaintext value map for the given project/scope, key-ascending
     *         ({@code LinkedHashMap}) so callers that need reproducible output (e.g. deterministic
     *         env-file content) can rely on iteration order.
     */
    Map<String, String> resolve(Long projectId, EnvironmentScope scope);
}
