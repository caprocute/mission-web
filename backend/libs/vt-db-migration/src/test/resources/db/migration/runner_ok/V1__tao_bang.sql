-- Migration mẫu cho kiểm thử MigrationRunner.
CREATE TABLE runner_ok.runner_probe (
    id uuid NOT NULL,
    CONSTRAINT pk_runner_probe PRIMARY KEY (id)
);
