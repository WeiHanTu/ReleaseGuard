# Control-plane packaging and installation

ReleaseGuard packages the Java operator, metric-gate service, and a Git-authored local environment. Argo Rollouts, Prometheus, and Argo CD remain external platform dependencies rather than chart subdependencies.

## Build and verify the images

```bash
RELEASEGUARD_OPERATOR_IMAGE=releaseguard-operator:dev \
  ./tests/container/operator-image.sh
RELEASEGUARD_GATE_IMAGE=releaseguard-metric-gate:dev \
  ./tests/container/metric-gate-image.sh
```

Both multi-stage builds pin the Temurin 21 builder and Distroless Java 21 runtime by image-index digest. Verification checks the configured non-root identity, Java startup, expected entrypoint and port, absence of a shell/source/build tree, and at most 50 MiB above the architecture-specific runtime base. The relative limit catches accidental builder-stage leakage without assuming that amd64 and arm64 base layers have equal sizes.

## Verify and install the chart

```bash
./scripts/install-helm.sh
./tests/helm/chart.sh
kubectl create namespace releaseguard-system
kubectl -n releaseguard-system create secret generic releaseguard-metric-gate-auth \
  --from-literal=token="$(openssl rand -hex 32)"
helm upgrade --install releaseguard charts/releaseguard \
  --namespace releaseguard-system \
  --set image.repository=ghcr.io/weihantu/releaseguard-operator \
  --set image.digest=sha256:<operator-digest> \
  --set metricGate.image.repository=ghcr.io/weihantu/releaseguard-metric-gate \
  --set metricGate.image.digest=sha256:<metric-gate-digest>
```

The checksum-pinned installer provides Helm v4.2.4. The chart test verifies schema rejection, byte-for-byte CRD freshness, both digest renderings, the external Secret reference, explicit RBAC, probes, and pod security. The gate runs behind a `ClusterIP` without a service-account token; the chart never renders its bearer token.

Helm installs `crds/` before the templates but does not upgrade CRDs that already exist. After an API change, `./gradlew :api-model:syncCrds` refreshes both `config/crd/` and the chart copy; review the diff and apply `config/crd/` before upgrading an existing release.

The operator supports active-passive high availability via Kubernetes Lease leader election (ADR 0009, RG-REC-007, RG-REC-008). By default, `operator.leaderElection.enabled` is true, enabling zero-downtime `RollingUpdate` deployments across multiple replicas (`replicaCount >= 1`). If leader election is disabled, `replicaCount` is restricted to 1 with `Recreate` strategy. The current operator watches all namespaces, so the chart uses a `ClusterRole`; its negative Kind checks prove that this identity cannot read Secrets or delete Namespaces.

## Clean-cluster proof

```bash
./scripts/install-kind.sh
./tests/e2e/kind-helm-install.sh
```

This test creates an ephemeral Kubernetes v1.35.0 cluster, installs checksum-verified Argo Rollouts v1.9.1, loads both locally verified images, and installs the chart with an ephemeral Secret. It checks both operator probes, gate probes and metrics, uniform `401` rejection, authorized request admission, and creates a real Argo Rollout through ReleaseGuard. It deletes the cluster on exit and retains evidence under ignored `build/e2e/` output.

## GitOps proof

```bash
./tests/e2e/kind-gitops.sh
```

The GitOps test builds on the clean Helm proof, installs Argo CD v3.5.2 from a checksum-verified upstream manifest, and applies `config/argocd/releaseguard-local.yaml`. It serves a disposable bare repository from a non-root, digest-pinned Git-daemon fixture so the current working tree is tested before publication. The test then pushes a second commit and only observes cluster state until Argo CD applies it; no `kubectl apply`, patch, or Argo refresh occurs after the push. Finally, it injects unauthorized metadata drift and waits for self-healing. Test-only reconciliation and repository-cache intervals are five seconds; they are not recommended production settings.

See `docs/GITOPS.md` for ownership boundaries and `docs/COMPATIBILITY.md` for pinned versions.
