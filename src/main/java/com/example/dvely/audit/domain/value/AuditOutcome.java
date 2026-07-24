package com.example.dvely.audit.domain.value;

/**
 * Terminal result of an audited action (design §2.1). Only two values by design (ADR-A5): this
 * table records completed facts, not in-flight/retryable states — a non-terminal failure (e.g. a
 * deployment's RETRY_WAIT transition) is deliberately never recorded at all rather than modeled as
 * a third outcome (see design §3.2 exclusion list).
 */
public enum AuditOutcome {
    SUCCEEDED,
    FAILED
}
