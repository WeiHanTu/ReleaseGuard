# ADR 0005: Bound and verify release finalization

- Status: Accepted
- Date: 2026-08-29

## Context

Deleting a `ReleasePlan` during a canary can either strand a finalizer or leave traffic on the candidate revision. Removing ownership too early is especially dangerous for `OrphanStable`, because the retained resources must route only to a ready stable revision. Argo Rollouts owns abort processing and ingress-nginx owns traffic routing, so ReleaseGuard must verify their observable state rather than infer completion from its own request.

## Decision

The operator adds `delivery.weihantu.dev/releaseguard-cleanup` and applies these steps:

1. Patch `status.abort=true` on the Argo Rollout status subresource.
2. Wait for both `status.abort=true` and `status.abortedAt`, which distinguishes a stored request from controller processing.
3. When a stable revision exists, require the stable Service selector to match `status.stableRS`, at least one ready stable endpoint, and an absent canary Ingress or `canary-weight=0`.
4. For `Delete`, delete all rendered children and verify their absence before removing the finalizer.
5. For `OrphanStable`, record the verified stable hash on the stable Service, detach the stable ReplicaSet, Service, and Ingress, delete the Rollout with orphan propagation, delete non-stable ReplicaSets and canary networking, and restore the stable Service selector if Argo clears it during Rollout deletion.
6. Remove the finalizer only after the Rollout is absent, exactly one detached stable ReplicaSet remains ready through the stable Service, and canary networking is absent.

Cleanup requeues every `1s` and has a `60s` deadline by default. Operators can set `RELEASEGUARD_CLEANUP_REQUEUE_INTERVAL` and `RELEASEGUARD_CLEANUP_DEADLINE` with positive Kubernetes durations. After the deadline, ReleaseGuard attempts to publish a degraded status, deletes all generated resources, and releases the finalizer. This fail-safe deliberately overrides `OrphanStable` rather than risk retaining an unverified traffic path.

Reconciliation failures use eight attempts with exponential backoff from `250ms` to at most `30s`.

## Consequences

Finalization is bounded and idempotent, but `OrphanStable` is best-effort after the deadline. API-server integration tests cover both policies; the test API server does not run Kubernetes garbage collection, so that test explicitly completes the orphan deletion after verifying the request. A checksum-pinned Kind scenario tests both policies against the real Argo Rollouts controller. It covers Argo outage/recovery for `Delete` and verifies that an `OrphanStable` stable workload remains ready without recreating canary resources after Argo and ReleaseGuard restart. It does not run ingress-nginx, so it proves control-plane state and ready endpoints rather than request routing. The required deletion audit event remains unimplemented until the outbox exists, so this slice does not fully satisfy `RG-DEL-003` and is not production evidence.
