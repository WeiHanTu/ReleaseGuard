# Repository Guidelines

## Project Structure & Module Organization

Treat `spec.md` as the normative behavior contract and `docs/PROJECT_PLAN.md` as the sequencing and evidence plan. Implemented Java code lives in `api-model/`, `release-operator/`, `metric-gate-service/`, and `cli/`; the history and demo service modules remain scaffolds. Keep Kubernetes resources in `config/`, Helm packaging in `charts/releaseguard/`, deployment definitions in `deploy/`, GKE infrastructure in `terraform/gke/`, cross-module tests in `tests/`, and operational material in `docs/`.

## Build, Test, and Development Commands

Use the committed Gradle wrapper; do not rely on a system Gradle installation.

```bash
./gradlew clean check
```

This is the required local gate. Use `./gradlew :api-model:test` while iterating and `./gradlew :api-model:syncCrds` after API changes; review `config/crd/`. Verify packaging with `./tests/container/operator-image.sh`, `./scripts/install-helm.sh`, and `./tests/helm/chart.sh`. Run clean-cluster GitOps synchronization with `./scripts/install-kind.sh && ./tests/e2e/kind-gitops.sh`; use `kind-helm-install.sh` for the chart-only path and `kind-finalizer.sh` for deletion behavior.

If Python tooling is introduced, manage it with `uv`, commit `pyproject.toml` and `uv.lock`, and use `uv sync --locked`. Do not add `pip install` instructions or an unmanaged `requirements.txt`.

## Coding Style & Naming Conventions

Target Java 21 and Gradle Kotlin DSL. Use four-space indentation, UTF-8, explicit imports, and no wildcard imports. Prefer immutable API models and small, deterministic functions for validation and state transitions. Use `UpperCamelCase` for types, `lowerCamelCase` for members, `UPPER_SNAKE_CASE` for constants, and kebab-case for Kubernetes resource names. Formatting and static-analysis rules configured in Gradle are authoritative.

## Testing Guidelines

Name unit tests `*Test`, integration tests `*IT`, and end-to-end scenarios by observable behavior. Cover invalid inputs, retries, duplicate delivery, restarts, and dependency failures. Link tests to applicable `RG-*` requirements. Never promote on missing, stale, or invalid metric evidence. Commit reproducible fixtures, not local runtime output.

## Commit & Pull Request Guidelines

Follow the existing imperative, sentence-case history: `Specify ReleaseGuard v1 behavior and contracts`. Keep commits atomic. Pull requests must explain the behavior change, cite affected requirement IDs or ADRs, list verification commands, and call out generated CRD/OpenAPI changes. Include diagrams or screenshots only when visual behavior changes. Do not merge with failing checks.

## Security, Scope, and Evidence

Never commit credentials, kubeconfigs, Terraform state, or local runtime data. Preserve the ownership boundaries in `README.md`: ReleaseGuard complements Argo CD and Argo Rollouts; it does not replace them. Distinguish targets from measured results, local/CI evidence from cloud experiments, and planned features from implemented behavior.
