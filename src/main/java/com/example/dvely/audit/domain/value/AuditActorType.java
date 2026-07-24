package com.example.dvely.audit.domain.value;

/**
 * Who caused an audited action (design §2.1 ADR-A8). Derived explicitly at each call site rather
 * than from a thread-local request context: {@code taskId != null} means an Agent-driven action,
 * a direct authenticated API call with no task means {@code USER}, and a webhook/worker-driven
 * terminal transition with no human in the loop means {@code SYSTEM}. Explicit parameter passing
 * was chosen over a thread-local specifically because several hook points run from {@code @Async}
 * worker threads (design F6/F8) where a request-scoped thread-local would simply be empty.
 */
public enum AuditActorType {
    USER,
    AGENT,
    SYSTEM
}
