# ADR 0006: Use resource-versioned primary status updates

- Status: Accepted
- Date: 2026-08-29

## Context

`ReleasePlan.status` authorizes later control-loop decisions, so a stale reconciliation must not overwrite newer observed state. Java Operator SDK 5.4 defaults returned primary-resource updates to server-side apply. Its non-SSA returned status patch also omits `metadata.resourceVersion`. Both approaches can merge a previously calculated status after another writer has changed the resource, which does not satisfy `RG-REC-003`.

Generated child resources have different semantics: ReleaseGuard owns documented fields and should continue applying them idempotently with server-side apply.

## Decision

The reconciler persists `ReleasePlan.status` explicitly through `ResourceOperations.updatePrimaryStatus`. This performs a status-subresource update carrying the resource version read for that reconciliation. The reconciler returns `UpdateControl.noUpdate` so the framework does not issue a second status patch.

A Kubernetes `409 Conflict` is allowed to escape the reconciliation attempt. When the concurrent update event is already present, the SDK immediately reconciles the latest informer state; otherwise its configured bounded retry schedules another attempt. Both paths execute validation, rendering, application, observation, and status calculation again. ReleaseGuard never retries only the stale status payload.

Child Services, Ingresses, and Argo Rollouts continue to use server-side apply with field manager `releaseguard-operator`.

## Consequences

Conflicts repeat the complete idempotent reconciliation path and may add API work under contention, but they cannot silently replace a newer phase or revision. A kube-apiserver integration test performs a concurrent status write immediately before the first status update, observes a real `409`, verifies a newer resource version on the next reconciliation, preserves the concurrent phase and revision evidence, and asserts that the four deterministic children are not duplicated. This is correctness evidence, not a throughput or production-scale claim.
