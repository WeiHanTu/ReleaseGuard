# ADR 0005: Align the gate contract with Argo AnalysisRun semantics

- Status: Accepted
- Date: 2026-08-31

## Context

The original v1alpha1 DSL named a consecutive-failure threshold, required callers to supply an evaluation timestamp, and assumed ReleaseGuard revision labels could be passed directly into an Argo web metric. Argo Rollouts v1.9.1 instead exposes a total `failureLimit`, cannot synthesize the current time in a web-provider body, and provides stable/latest pod-template hashes to analysis arguments.

Pretending these contracts were equivalent would make the DSL lie about failure behavior and would prevent a generated template from calling the gate correctly.

## Decision

Replace `consecutiveFailures` with the exact Argo-aligned `failureLimit` and add an explicit `inconclusiveLimit`. Keep `consecutiveSuccesses`, which maps to Argo's `consecutiveSuccessLimit`. Because the API is still v1alpha1 and has no compatibility guarantee, make this correction before broader use.

Allow `evaluationTime` to be omitted. The gate assigns its UTC server time before validation, querying, and evidence hashing; explicit timestamps remain supported for deterministic replay and tests.

Generate one policy-versioned `AnalysisTemplate` per release generation. It references a bearer token through `secretKeyRef`; the operator never reads, copies, or renders the token. Platform automation must provision the named Secret in each release namespace. Future Rollout integration will pass Argo stable/latest pod-template hashes as metric revision identities.

Encode ReleaseGuard's nested PromQL placeholders through default Argo arguments. Argo performs one argument-substitution pass across the serialized metric; the indirection restores `{{revision}}`, `{{window}}`, `{{namespace}}`, and `{{releaseName}}` in the HTTP body without asking Argo to resolve them itself.

## Consequences

The generated template can be validated and executed independently while Rollouts remain fail-closed on indefinite pauses. Existing v1alpha1 manifests must rename the failure field and add an inconclusive limit. Application telemetry or Prometheus relabeling must expose the Argo pod-template hash used by policy queries.
