# Operator packaging and installation

ReleaseGuard packages the Java operator and a Git-authored local environment. Argo Rollouts and Argo CD remain external platform dependencies rather than chart subdependencies.

## Build and verify the image

```bash
RELEASEGUARD_OPERATOR_IMAGE=releaseguard-operator:dev \
  ./tests/container/operator-image.sh
```

The multi-stage build pins the Temurin 21 builder and Distroless Java 21 runtime by image-index digest. Verification checks the configured non-root identity, Java startup, expected entrypoint and port, absence of a shell/source/build tree, and at most 50 MiB above the architecture-specific runtime base. The relative limit catches accidental builder-stage leakage without assuming that amd64 and arm64 base layers have equal sizes.

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

## GitOps proof

```bash
./tests/e2e/kind-gitops.sh
```

The GitOps test builds on the clean Helm proof, installs Argo CD v3.5.2 from a checksum-verified upstream manifest, and applies `config/argocd/releaseguard-local.yaml`. It serves a disposable bare repository from a non-root, digest-pinned Git-daemon fixture so the current working tree is tested before publication. The test then pushes a second commit and only observes cluster state until Argo CD applies it; no `kubectl apply`, patch, or Argo refresh occurs after the push. Finally, it injects unauthorized metadata drift and waits for self-healing. Test-only reconciliation and repository-cache intervals are five seconds; they are not recommended production settings.

See `docs/GITOPS.md` for ownership boundaries and `docs/COMPATIBILITY.md` for pinned versions.
