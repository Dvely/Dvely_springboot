package com.example.dvely.change.domain.value;

public enum ChangeStatus {
    PREVIEW_READY,
    DEPLOYED,
    // Track Z (#56): the change's diff was approved (RESULT approval) and merged preview -> main.
    MERGED,
    // Track Z (#56): the change's diff was rejected — never merged, preview branch left as-is
    // (design D6 "누적" — a later task's changes build on top of it, and the *next* successful
    // RESULT approval reflects the whole preview state, rejected commits included).
    REJECTED
}
