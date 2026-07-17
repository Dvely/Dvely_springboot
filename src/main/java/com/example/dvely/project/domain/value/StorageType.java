package com.example.dvely.project.domain.value;

/**
 * OBJECT_STORAGE covers the S3/GCS-family use case. Block storage is implicit in the server
 * choice and managed database connections are explicitly out of scope for this unit
 * (BACKLOG BI-127, design D3).
 */
public enum StorageType {
    NONE,
    OBJECT_STORAGE
}
