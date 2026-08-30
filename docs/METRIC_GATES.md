# Metric-Gate Service

## Current boundary

The metric-gate service implements the stateless portions of `RG-GATE-001` through `RG-GATE-011`. It does not yet implement the AnalysisRun limits and pause behavior required to complete `RG-GATE-010`, shift traffic, count consecutive results, or mutate a `ReleasePlan`. Argo Rollouts will own those responsibilities after AnalysisRun integration. The service is packaged and authenticated for cluster-internal deployment, but it is not yet called by generated AnalysisRuns.

```mermaid
flowchart LR
    Analysis["Argo AnalysisRun (planned)"] --> API["POST /internal/v1/evaluations"]
    API --> Validate["Request and policy validation"]
    Validate --> Render["Escaped PromQL rendering"]
    Render --> Query["Bounded Prometheus queries"]
    Query --> Decide["Deterministic gate decision"]
    Decide --> Digest["Canonical SHA-256 evidence"]
```

## Evaluation contract

A request snapshots the release ID, step, mode, policy specification and resource version, stable/canary revisions, thresholds, and evaluation time. `Bootstrap` forbids a stable revision and requires an absolute threshold. `Canary` requires different stable and canary revisions.

Each revision uses separate value and sample-count PromQL queries. Substitutions for revision, namespace, and release name must occur inside string literals and are escaped; the validated duration window must remain outside quotes. The service applies both the policy timeout and the smaller remaining overall request deadline.

| Evidence | Decision | Representative reason |
|---|---|---|
| Enough samples and all configured predicates pass | `Pass` | `WithinAbsoluteAndBaselineLimits` |
| Complete evidence breaches an absolute or baseline threshold | `Fail` | `AbsoluteThresholdBreached` |
| Evidence is unsafe or unavailable | `Inconclusive` | `EmptyVector`, `StaleObservation`, `ProviderTimeout` |

`Inconclusive` is never success and does not independently request rollback. AnalysisRun inconclusive limits will pause progression when monitoring is unavailable.

## Run and verify

```bash
RELEASEGUARD_GATE_AUTH_TOKEN="$(openssl rand -hex 32)" \
PROMETHEUS_BASE_URL=http://127.0.0.1:9090 \
  ./gradlew :metric-gate-service:run

./gradlew :metric-gate-service:test
curl --fail http://127.0.0.1:8080/actuator/health
```

The default overall deadline is 10 seconds and can be changed with `GATE_REQUEST_TIMEOUT` up to one minute. `POST /internal/v1/evaluations` requires `Authorization: Bearer <token>`; missing and invalid credentials receive the same `401` response. Tokens must contain at least 32 UTF-8 bytes and are redacted from configuration rendering. Health and metrics remain unauthenticated for Kubernetes probes and cluster-local Prometheus scraping.

The Helm chart deploys the service behind a `ClusterIP`, disables service-account-token mounting, and reads the bearer token through a reference to `metricGate.auth.existingSecret`. It never creates the Secret or embeds the token in a Deployment. `/actuator/prometheus` exposes query latency/outcomes, gate decisions/reasons, JVM, process, and HTTP server metrics without release IDs or revisions as labels.

The current Kind profile does not terminate TLS or install a NetworkPolicy, so the bearer token travels over the cluster network. That is acceptable only for the documented disposable local environment. A cloud deployment must add mesh or service TLS and namespace-scoped network policy before this boundary can be called production-safe.

## Evidence integrity

The response digest is SHA-256 over canonical JSON containing the complete request snapshot, rendered query metadata, normalized observations, decision, reason, and query window. The same request and observations therefore produce the same digest. Provider bodies, credentials, and authorization headers are never included in responses or logs.
