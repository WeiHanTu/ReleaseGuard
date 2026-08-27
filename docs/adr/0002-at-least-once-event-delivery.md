# ADR 0002: Use a Kubernetes Outbox and At-Least-Once Delivery

- Status: Accepted
- Date: 2026-08-26

## Context

Kubernetes status updates and Kafka publication cannot share a transaction. Claiming exactly-once delivery across them would hide a crash window rather than remove it.

## Decision

The operator persists a deterministic `ReleaseEvent` resource before publication. Event IDs and names derive from release UID, generation, sequence, type, and canonical payload. Kafka publication is at least once, keyed by release UID. Consumers deduplicate by event ID.

ReleasePlan status retains enough transition identity to recreate a missing outbox resource. Kafka acknowledgment followed by a process crash may cause a duplicate after restart; that outcome is permitted and tested.

## Consequences

Consumers must be idempotent and preserve per-release ordering. Pending events are never removed by TTL. Audit backpressure may pause promotion, but it cannot block abort or rollback.
