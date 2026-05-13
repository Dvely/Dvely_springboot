ALTER TABLE deployment_histories
    ADD COLUMN workflow_run_id BIGINT NULL AFTER status;
