# ReleaseGuard Helm chart

This chart installs the ReleaseGuard operator, metric-gate service, and four CRDs. Argo Rollouts and Prometheus are external dependencies. The chart grants cluster-wide reconciliation because the current operator watches `ReleasePlan` resources in every namespace.

Helm installs files under `crds/` only when a CRD is absent; it does not upgrade existing CRDs. After an API-model change, run `./gradlew :api-model:syncCrds`, review both generated copies, and apply `config/crd/` before upgrading an existing release.

Build both local images, create the gate's bearer-token Secret, load the images into the target cluster, and install:

```bash
docker build -t releaseguard-operator:dev .
docker build -f metric-gate-service/Dockerfile -t releaseguard-metric-gate:dev .
kubectl create namespace releaseguard-system
kubectl -n releaseguard-system create secret generic releaseguard-metric-gate-auth \
  --from-literal=token="$(openssl rand -hex 32)"
# Provision the same Secret and token in each ReleasePlan namespace, preferably via External Secrets.
helm upgrade --install releaseguard charts/releaseguard \
  --namespace releaseguard-system
```

For a published release, use an immutable digest:

```bash
helm upgrade --install releaseguard charts/releaseguard \
  --namespace releaseguard-system --create-namespace \
  --set image.repository=ghcr.io/weihantu/releaseguard-operator \
  --set image.digest=sha256:<operator-digest> \
  --set metricGate.image.repository=ghcr.io/weihantu/releaseguard-metric-gate \
  --set metricGate.image.digest=sha256:<metric-gate-digest>
```

The pods run as UID/GID 65532 with read-only root filesystems, all Linux capabilities dropped, and the runtime-default seccomp profile. The metric gate does not mount a service-account token and is exposed only through a `ClusterIP` Service. Its evaluation API requires the token from `metricGate.auth.existingSecret`; the chart references that Secret but never creates or renders its contents. Generated AnalysisTemplates use `operator.analysis.auth.existingSecret` and `tokenKey` in the ReleasePlan namespace. ReleaseGuard never reads or copies Secrets, so platform automation must provision the matching credential in every target namespace. Argo resolves that reference when it starts an AnalysisRun: a namespace without the Secret fails the run closed rather than promoting the candidate. The ClusterRole grants AnalysisRun access because the operator creates the bootstrap evaluation itself and removes analysis children during finalization.

The operator's `/healthz` reports process liveness and `/readyz` becomes healthy only after Java Operator SDK startup. The metric gate uses Spring Boot liveness and readiness probes. Set `metricGate.prometheusBaseUrl` to the cluster-local Prometheus URL before evaluating a release. `operator.analysis.evaluationUrl` defaults to the chart's cluster-local metric-gate Service and can point to another HTTP(S) endpoint.

Only one operator replica is accepted. The operator Deployment uses `Recreate` so an upgrade cannot briefly run competing reconcilers before leader election is implemented.
