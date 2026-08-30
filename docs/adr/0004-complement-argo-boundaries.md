# ADR 0004: Complement Argo CD and Argo Rollouts

- Status: Accepted
- Date: 2026-08-26

## Context

Building another Git synchronizer, traffic controller, or ReplicaSet manager would duplicate mature systems and dilute the project’s control-plane evidence.

## Decision

Argo CD owns Git-to-cluster synchronization. Argo Rollouts owns ReplicaSets, traffic weights, pauses, aborts, AnalysisRun lifecycle, and the pod-template-hash component of stable/canary Service selectors. ReleaseGuard owns its constrained deployment DSL, policy validation, orchestration decisions, resource rendering, metric-gate contract, operational actions, and audit evidence.

ReleaseGuard generates Argo and ingress resources with deterministic names and reconciles only documented fields. Service identity selectors exclude release generation so they remain valid across Rollout updates. ReleaseGuard initializes a Service selector, then omits the selector from later server-side applies after Argo installs a pod-template hash; it observes and verifies Argo's result instead of competing for the field. Other field-manager conflicts are reported instead of silently forced.

## Consequences

ReleaseGuard cannot claim Argo capabilities as its own. Integration failures and version compatibility become explicit test surfaces. The v1 implementation can focus on a reliable control loop rather than another deployment engine.
