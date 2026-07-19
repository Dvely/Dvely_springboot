SET NAMES utf8mb4;

-- Issue #70 (QA v2 §3, Critical) — V21 declared next_attempt_at NOT NULL, but
-- SpringDataWebhookDeliveryRepository#claim intentionally sets it to NULL when it moves a
-- delivery to PROCESSING (see that repository's Javadoc-equivalent inline comment): next_attempt_at
-- only carries meaning for rows sitting in the retry queue (PENDING/RETRY_WAIT) — a PROCESSING
-- (or terminal COMPLETED/IGNORED/FAILED) row has no "next attempt" to speak of, and
-- WebhookDelivery#clearQueue()/#complete()/#ignore() null it out for exactly that reason. The NOT
-- NULL constraint contradicted that domain invariant, so every real claim() UPDATE threw
-- SQLIntegrityConstraintViolationException and the webhook worker could never advance past its
-- first claim — a full pipeline stall, not merely a degraded path.
--
-- DEFAULT CURRENT_TIMESTAMP is intentionally kept: the application always supplies an explicit
-- value on INSERT (WebhookDelivery's 3-arg constructor sets next_attempt_at = now()), so the
-- default never actually fires from this codebase's own write paths, but preserving it keeps any
-- other/future direct-SQL insert defaulting to "immediately claimable" rather than accidentally
-- landing NULL (which would misrepresent a freshly-queued row as "in progress").
ALTER TABLE webhook_deliveries
    MODIFY COLUMN next_attempt_at DATETIME NULL DEFAULT CURRENT_TIMESTAMP;
