# ADR 0007: Drive canary steps with the generated analysis template

- Status: Accepted
- Date: 2026-08-31

## Context

ADR 0005 generated one policy-versioned `AnalysisTemplate` per release generation but left every nonterminal canary step as an indefinite Argo `pause`. That was fail-closed and honest, but nothing consumed the template: no release could promote or roll back without a human, and the declared step `duration` had no meaning at all.

Argo Rollouts already owns the only three step outcomes ReleaseGuard needs. An inline `analysis` step blocks progression until its AnalysisRun terminates, then advances on `Successful`, aborts the rollout on `Failed` or `Error`, and pauses it on `Inconclusive`. Those map exactly onto the `failurePolicy` this DSL already requires: `gateFailed: Rollback` and `metricUnavailable: Pause`.

## Decision

Render each nonterminal step as `setWeight` followed by an `analysis` step that references the generation's template and supplies `step`, `mode=Canary`, and the Argo stable/latest pod-template hashes as revision identities. Weight 100 stays a bare `setWeight`, so terminal promotion can only be reached through every preceding gate (RG-ROLL-008).

Give each metric an `initialDelay` of the policy window. A measurement taken before one full window has elapsed judges the candidate on samples that predate the traffic shift; the gate would correctly answer `Inconclusive` and spend the inconclusive budget on its own warm-up.

Reject plans whose step duration cannot contain that schedule (RG-ROLL-004). The admissible budget for a gate is `window + (max(failureLimit, consecutiveSuccesses, inconclusiveLimit) - 1) x interval`. A step duration shorter than that guarantees a pause rather than a decision, so it is a plan error rather than a runtime surprise.

Do not render Argo's `count`. When a counted metric exhausts its measurements without breaching a limit, Argo assesses it `Successful` -- promotion by exhaustion. ReleaseGuard promotes only on an explicit consecutive-success streak, so the three limits stay the only terminal conditions.

Set `consecutiveErrorLimit` to the declared `inconclusiveLimit`, and set `progressDeadlineSeconds` to the longest declared step plus five minutes so a legitimately long observation window is not reported as a stalled rollout.

Project the resulting outcomes into `ReleasePlan` status: full promotion becomes `Succeeded`, an abort becomes `RollingBack` and then `RolledBack` only once the canary ingress weight is zero and the stable Service has ready endpoints (RG-RBK-002), and an `InconclusiveAnalysisRun` pause condition becomes `Paused`. Record the AnalysisRun name and verdict as `status.lastGateDecision`.

## Consequences

A healthy candidate now promotes and a failing candidate now rolls back without human action, and the step duration became a checked observation budget instead of decoration.

Every release namespace must contain the gate token Secret before a canary step runs. Argo resolves that `secretKeyRef` when it starts the run; a missing Secret fails the run closed and aborts the candidate rather than promoting it.

A gate-service outage is not the same failure as a Prometheus outage. Prometheus loss reaches Argo as an `Inconclusive` decision and pauses the rollout, which is what RG-GATE-010 requires. An unreachable gate service produces transport errors, and after `consecutiveErrorLimit` of them Argo aborts the candidate. That is fail-closed but it is a rollback caused by monitoring loss, and it is recorded here rather than hidden.

The wall-clock length of a step is bounded by the gate outcome, not by the declared duration. Validation guarantees the budget is achievable; it does not truncate a flapping analysis. Bounding a stuck step, enforcing `rollbackDeadline`, and resuming a paused release through `ReleaseAction` remain unimplemented.

Bootstrap is unchanged and still incomplete. Argo skips canary steps when no stable revision exists, so the generated analysis never runs for a first generation, and the release stays in `Bootstrapping` until bootstrap gate handling is implemented (RG-BOOT-002).
