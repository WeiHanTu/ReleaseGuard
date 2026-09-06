-- ReleaseGuard Audit Schema V1 (RG-AUD-001 through RG-AUD-006)

CREATE TABLE IF NOT EXISTS events (
    event_id VARCHAR(128) PRIMARY KEY,
    release_id VARCHAR(128) NOT NULL,
    release_uid VARCHAR(128) NOT NULL,
    generation BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    release_name VARCHAR(64) NOT NULL,
    git_revision VARCHAR(128),
    image_digest VARCHAR(128),
    trace_id VARCHAR(128),
    payload TEXT NOT NULL,
    kafka_topic VARCHAR(64),
    kafka_partition INT,
    kafka_offset BIGINT,
    ingested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_events_release_seq ON events (namespace, release_name, sequence);
CREATE INDEX IF NOT EXISTS idx_events_release_id_seq ON events (release_id, sequence);

CREATE TABLE IF NOT EXISTS releases (
    release_id VARCHAR(128) PRIMARY KEY,
    release_uid VARCHAR(128) NOT NULL,
    namespace VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    phase VARCHAR(32) NOT NULL,
    current_step INT,
    stable_revision VARCHAR(128),
    canary_revision VARCHAR(128),
    last_transition_sequence BIGINT NOT NULL,
    last_event_id VARCHAR(128) NOT NULL,
    last_event_type VARCHAR(64) NOT NULL,
    last_event_at TIMESTAMP WITH TIME ZONE NOT NULL,
    gate_decision VARCHAR(32),
    gate_evidence_ref VARCHAR(256),
    gate_evidence_digest VARCHAR(128),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_releases_ns_name ON releases (namespace, name, generation DESC);

CREATE TABLE IF NOT EXISTS projection_checkpoints (
    topic VARCHAR(64) NOT NULL,
    partition_id INT NOT NULL,
    last_offset BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic, partition_id)
);

CREATE TABLE IF NOT EXISTS quarantined_events (
    quarantine_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    release_id VARCHAR(128) NOT NULL,
    sequence BIGINT NOT NULL,
    expected_sequence BIGINT,
    reason VARCHAR(512) NOT NULL,
    raw_envelope TEXT NOT NULL,
    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_quarantined_release_id ON quarantined_events (release_id);
