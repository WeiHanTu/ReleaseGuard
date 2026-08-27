# ADR 0001: Separate Desired, Observed, and Audit State

- Status: Accepted
- Date: 2026-08-26

## Context

ReleaseGuard spans Git, Kubernetes, Kafka, and PostgreSQL. Treating all four as interchangeable state stores would create split-brain behavior and make recovery ambiguous.

## Decision

Git-authored `ReleasePlan` and `MetricPolicy` resources are desired state. Kubernetes resource status is authoritative observed control-plane state. `ReleaseEvent` resources are the durable publication outbox. Kafka and PostgreSQL are replayable audit state and never authorize rollout progression.

The operator derives children only from the accepted Kubernetes generation and records `observedGeneration`. It does not reconstruct rollout authority from Kafka or PostgreSQL.

## Consequences

Temporary audit-path failures cannot prevent abort or rollback. PostgreSQL can be rebuilt by replaying Kafka. The operator must explicitly reconcile drift between Git-authored desired state and observed cluster state.
