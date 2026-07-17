package com.example.dvely.environment.domain.value;

/**
 * Deployment target a variable applies to. Only these two exist by design (see U3 design doc
 * D2) — there is no COMMON scope; a value meant for both must be created twice by the caller.
 * Adding COMMON later would require defining an override rule (scope-specific wins over COMMON),
 * which this MVP intentionally defers.
 */
public enum EnvironmentScope {
    PREVIEW,    // Docker preview container injection (future integration, see EnvironmentValueResolver)
    PRODUCTION  // Deployment workflow injection (future integration, see EnvironmentValueResolver)
}
