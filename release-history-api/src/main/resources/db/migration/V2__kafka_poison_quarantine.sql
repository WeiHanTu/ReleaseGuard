CREATE TABLE IF NOT EXISTS kafka_poison_records (
    topic VARCHAR(249) NOT NULL,
    partition_id INT NOT NULL,
    record_offset BIGINT NOT NULL,
    record_key TEXT,
    raw_value TEXT,
    reason VARCHAR(512) NOT NULL,
    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic, partition_id, record_offset)
);
