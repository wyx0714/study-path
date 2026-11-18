CREATE TABLE IF NOT EXISTS program_snapshot (
    program_id VARCHAR(32) NOT NULL,
    snapshot_date VARCHAR(10) NOT NULL,
    country VARCHAR(4) NOT NULL,
    track VARCHAR(24) NOT NULL,
    intake_year INT NOT NULL,
    definition TEXT NOT NULL,
    PRIMARY KEY (program_id, snapshot_date)
);

CREATE TABLE IF NOT EXISTS assessment (
    id VARCHAR(36) PRIMARY KEY,
    created_ms BIGINT NOT NULL,
    report TEXT NOT NULL
);
