# ADR 0008: Gate the bootstrap release with an operator-created AnalysisRun

- Status: Accepted
- Date: 2026-08-31

## Context

ADR 0007 gated every canary step, but a first generation never reaches one. Argo Rollouts skips canary steps while no stable revision exists: it scales the candidate straight to full traffic and reports `Healthy`. ReleaseGuard therefore had no evidence for its first release and parked it in `Bootstrapping` forever, while RG-BOOT-002 requires pod readiness *and* an administrator-approved absolute gate before a candidate becomes stable.

A bootstrap release is also the one case where a canary gate is meaningless. There is no baseline, so a threshold expressed only as a delta from stable cannot be evaluated, and RG-GATE-011 forbids fabricating or reusing one.

## Decision

The operator creates the bootstrap evaluation itself as a generation-scoped `AnalysisRun` named `<release>-bootstrap-g<generation>`, owned and labeled like every other generated child. It carries the same policy-versioned metrics as the canary template, filtered to gates that declare an absolute canary threshold, with `mode=Bootstrap`, an empty stable revision, and the observed pod-template hash as the candidate revision. A plan whose gates are all baseline-only cannot bootstrap, and rendering refuses it rather than evaluating nothing.

Readiness is asserted from the traffic path, not from Rollout status alone: the run is created only once Argo reports a healthy, fully promoted revision *and* the stable Service selector matches it with ready endpoints.

Outcomes map as follows. `Successful` admits the candidate: the release becomes `Succeeded` and records its first stable revision. `Failed` is a breached administrator threshold: the release becomes `Failed`, ReleaseGuard scales the generated Rollout to zero replicas, and it claims no rollback (RG-BOOT-003). `Inconclusive` or a provider `Error` is missing evidence, not bad evidence: the release stays in `Bootstrapping` with a degraded condition and is never admitted.

That last mapping is deliberately asymmetric with the canary path, where a transport error aborts the rollout. On a canary step Argo owns the decision and ReleaseGuard can only report it; during bootstrap ReleaseGuard owns it, and tearing down a first deployment because monitoring was unreachable is not a safety property.

Because the bootstrap run is not a Rollout child, the reconciler would never hear about its verdict through existing informers. ReleaseGuard-owned runs carry the managed-by label, so one more informer maps them back to their plan; Argo's own step runs do not carry it and continue to arrive through Rollout status.

## Consequences

A first release now becomes stable only on measured evidence, and the state machine has no dead end at `Bootstrapping` for a healthy or an unhealthy candidate.

Stopping candidate traffic means scaling to zero. There is no stable revision to restore, so serving nothing is the only safe state; the deployment, its ReplicaSet, the AnalysisRun, and the recorded decision all remain as evidence, and a corrected generation re-applies its declared replica count because ReleaseGuard keeps ownership of that field.

A bootstrap release whose evidence is inconclusive stays unadmitted and needs a human. `RetryAnalysis` as a `ReleaseAction` (RG-ACT-*) is not implemented, so today that means correcting the plan or the metric source and pushing a new generation.

The operator now creates and deletes AnalysisRuns, which the chart's ClusterRole grants explicitly. Finalization deletes ReleaseGuard-owned bootstrap runs and the step runs Argo created for the release, including on `OrphanStable`, where the Rollout is deleted without cascading.

`release.bootstrapped` (RG-BOOT-004) is still not emitted: the durable event outbox is Week 5 work, so the successful bootstrap is currently recorded only in `ReleasePlan` status.
