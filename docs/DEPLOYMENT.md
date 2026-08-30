# Operator packaging and installation

ReleaseGuard currently packages only the Java operator. Argo Rollouts must be installed first; Argo CD integration is the next Week 3 slice.

## Build and verify the image

```bash
RELEASEGUARD_OPERATOR_IMAGE=releaseguard-operator:dev \
  ./tests/container/operator-image.sh
```

The multi-stage build pins the Temurin 21 builder and Distroless Java 21 runtime by image-index digest. Verification checks the configured non-root identity, Java startup, expected entrypoint and port, absence of a shell/source/build tree, and a 150 MiB ceiling intended to catch accidental builder-stage leakage.

## Verify and install the chart

```bash
./scripts/install-helm.sh
./tests/helm/chart.sh
helm upgrade --install releaseguard charts/releaseguard \
  --namespace releaseguard-system --create-namespace \
  --set image.repository=ghcr.io/weihantu/releaseguard-operator \
  --set image.digest=sha256:<64-hex-character-digest>
```

The checksum-pinned installer provides Helm v4.2.4. The chart test verifies schema rejection, byte-for-byte CRD freshness, digest rendering, explicit RBAC, probes, and pod security.

Helm installs `crds/` before the templates but does not upgrade CRDs that already exist. After an API change, `./gradlew :api-model:syncCrds` refreshes both `config/crd/` and the chart copy; review the diff and apply `config/crd/` before upgrading an existing release.

The chart deliberately accepts exactly one replica and uses `Recreate` upgrades. Running overlapping reconcilers before leader election would be unsafe. The current operator watches all namespaces, so the chart uses a `ClusterRole`; its negative Kind checks prove that this identity cannot read Secrets or delete Namespaces.

## Clean-cluster proof

```bash
./scripts/install-kind.sh
./tests/e2e/kind-helm-install.sh
```

This test creates an ephemeral Kubernetes v1.35.0 cluster, installs checksum-verified Argo Rollouts v1.9.1, loads the locally verified image, installs the chart, checks `/healthz` and `/readyz`, and creates a real Argo Rollout through ReleaseGuard. It deletes the cluster on exit and retains evidence under ignored `build/e2e/` output.
