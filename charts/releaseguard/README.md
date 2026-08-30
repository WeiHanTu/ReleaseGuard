# ReleaseGuard Helm chart

This chart installs the ReleaseGuard operator and its four CRDs. Argo Rollouts is an external dependency and must already be installed. The chart grants cluster-wide reconciliation because the current operator watches `ReleasePlan` resources in every namespace.

Helm installs files under `crds/` only when a CRD is absent; it does not upgrade existing CRDs. After an API-model change, run `./gradlew :api-model:syncCrds`, review both generated copies, and apply `config/crd/` before upgrading an existing release.

Build the local image, load it into the target cluster, and install:

```bash
docker build -t releaseguard-operator:dev .
helm upgrade --install releaseguard charts/releaseguard \
  --namespace releaseguard-system --create-namespace
```

For a published release, use an immutable digest:

```bash
helm upgrade --install releaseguard charts/releaseguard \
  --namespace releaseguard-system --create-namespace \
  --set image.repository=ghcr.io/weihantu/releaseguard-operator \
  --set image.digest=sha256:<64-hex-character-digest>
```

The pod runs as UID/GID 65532 with a read-only root filesystem, all Linux capabilities dropped, and the runtime-default seccomp profile. `/healthz` reports process liveness; `/readyz` becomes healthy only after the Java Operator SDK starts.

Only one replica is accepted. The Deployment uses `Recreate` so an upgrade cannot briefly run competing reconcilers before leader election is implemented.
