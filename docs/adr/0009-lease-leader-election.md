# ADR 0009: Kubernetes Lease leader election for multi-replica operator HA

- Status: Accepted
- Date: 2026-09-06

## Context

ReleaseGuard requires deterministic reconciliation and conflict-free status updates across `ReleasePlan`, `ReleaseAction`, and `ReleaseEvent` resources (`RG-REC-001` through `RG-REC-006`). Previously, the operator ran as a single replica (`replicaCount: 1`) with a `Recreate` deployment strategy. If multiple operator replicas were deployed concurrently without coordination, both replicas would run independent reconciliation loops, resulting in dual-primary write contention, status update conflicts, and duplicate event publishing.

However, a single-replica `Recreate` operator creates an availability gap during node maintenance, pod eviction, kernel patching, or operator binary rollouts. To satisfy `RG-REC-007` (clean failover without dual-primary write conflict) and `RG-REC-008` (active-passive coordination via Kubernetes `coordination.k8s.io/v1` `Lease`), ReleaseGuard requires active-passive multi-replica coordination.

## Decision

We introduce active-passive leader election for `release-operator` backed by standard Kubernetes `coordination.k8s.io/v1` `Lease` resources.

1. **Active-Passive Lifecycle**:
   - Exactly one operator instance holds the Kubernetes `Lease` and actively runs controller reconciliation loops (`ReleasePlanReconciler`, `ReleaseActionReconciler`, `ReleaseEventReconciler`).
   - Standby replicas start and mark themselves ready via `OperatorHealthServer` (`/readyz`, `/healthz`), but withhold controller informer execution until leadership is acquired.
   - If the active leader terminates or fails to renew within the lease duration, standby replicas compete via Kubernetes compare-and-swap on the `Lease` resource. The winning replica seamlessly assumes leadership and starts its reconcilers.

2. **Configuration and Defaults**:
   - Environment variables configure leader election: `RELEASEGUARD_LEADER_ELECTION_ENABLED`, `RELEASEGUARD_LEASE_NAME`, `RELEASEGUARD_LEASE_NAMESPACE` (falling back to `POD_NAMESPACE`), `RELEASEGUARD_LEADER_IDENTITY` (falling back to `POD_NAME` or `HOSTNAME`), `RELEASEGUARD_LEASE_DURATION`, `RELEASEGUARD_LEASE_RENEW_DEADLINE`, and `RELEASEGUARD_LEASE_RETRY_PERIOD`.
   - Default lease timings follow Kubernetes recommendations: 15s lease duration, 10s renew deadline, and 2s retry period.

3. **Helm Packaging & Deployment Strategy**:
   - When leader election is enabled (`operator.leaderElection.enabled: true`, the default), `charts/releaseguard` configures the operator Deployment strategy as `RollingUpdate` (`maxSurge: 1`, `maxUnavailable: 0`), allowing seamless zero-downtime rolling upgrades.
   - The Helm schema lifts the previous single-replica limitation (`enum: [1]`), permitting `replicaCount >= 1`.
   - To prevent operator misconfiguration, the template fails closed if `replicaCount > 1` while `operator.leaderElection.enabled=false`.
   - The chart `ClusterRole` explicitly grants verbs (`get`, `list`, `watch`, `create`, `update`, `patch`, `delete`) on `coordination.k8s.io/leases`.

## Consequences

- Multi-replica high availability is enabled out of the box with zero split-brain reconciliation risk.
- Rolling updates of the operator pod no longer cause control plane outages or drop webhooks/informers.
- Standby replicas are immediately ready to serve health checks, satisfying Kubernetes readiness probes during rolling upgrades.
- Failover time is bounded by the lease duration (`~15s` default).
