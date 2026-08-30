# Compatibility matrix

The following versions are pinned by the executable local/CI paths. “Verified” means exercised on ephemeral Kind clusters, not vendor support or production certification.

| Component | Pinned version | Verification |
|---|---:|---|
| Java | 21 | Gradle compile, test, and operator runtime |
| Gradle | 9.7.1 | Wrapper checksum and `clean check` |
| Kubernetes | 1.35.0 | Digest-pinned Kind node image |
| Kind | 0.31.0 | Checksum-pinned installer |
| Helm | 4.2.4 | Checksum-pinned installer, lint, and chart install |
| Argo Rollouts | 1.9.1 | Checksum-pinned manifest and live reconciliation |
| Argo CD | 3.5.2 | Checksum-pinned manifest, Git sync, and self-healing |

Re-run `./gradlew clean check`, `./tests/helm/chart.sh`, `./tests/e2e/kind-gitops.sh`, and `./tests/e2e/kind-finalizer.sh` before changing a pinned version. Update this matrix only after both clean-cluster paths pass.
