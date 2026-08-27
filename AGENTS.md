# Repository Guidelines

## Project Structure & Module Organization

ReleaseGuard is currently design-first. Treat `spec.md` as the normative behavior contract and `docs/PROJECT_PLAN.md` as the sequencing and evidence plan. The implementation will use Gradle modules: `api-model/`, `release-operator/`, `metric-gate-service/`, `release-history-api/`, `cli/`, and `demo-service/`. Keep Kubernetes resources in `config/`, Helm packaging in `charts/releaseguard/`, deployment definitions in `deploy/`, GKE infrastructure in `terraform/gke/`, cross-module tests in `tests/`, and operational material in `docs/`.

## Build, Test, and Development Commands

Use the committed Gradle wrapper; do not rely on a system Gradle installation.

```bash
./gradlew clean check
```

This is the required local gate. Use `./gradlew :api-model:test` while iterating and `./gradlew :api-model:syncCrds` after intentional API changes; review the resulting `config/crd/` diff. Run later Kind, Helm, and cloud workflows only through committed scripts or Gradle tasks so CI and reviewers can reproduce them. Do not document commands that do not yet exist.

If Python tooling is introduced, manage it with `uv`, commit `pyproject.toml` and `uv.lock`, and use `uv sync --locked`. Do not add `pip install` instructions or an unmanaged `requirements.txt`.

## Coding Style & Naming Conventions

Target Java 21 and Gradle Kotlin DSL. Use four-space indentation, UTF-8, explicit imports, and no wildcard imports. Prefer immutable API models and small, deterministic functions for validation and state transitions. Use `UpperCamelCase` for types, `lowerCamelCase` for members, `UPPER_SNAKE_CASE` for constants, and kebab-case for Kubernetes resource names. Formatting and static-analysis rules configured in Gradle are authoritative.

## Testing Guidelines

Name unit tests `*Test`, integration tests `*IT`, and end-to-end scenarios by observable behavior. Cover happy paths, invalid inputs, retries, duplicate delivery, restarts, and dependency failures. Link tests to applicable `RG-*` requirements from `spec.md`. Never promote on missing, stale, or invalid metric evidence. Commit reproducible fixtures and summarized evidence; exclude unreviewed local benchmark output.

## Commit & Pull Request Guidelines

Follow the existing imperative, sentence-case history: `Specify ReleaseGuard v1 behavior and contracts`. Keep commits atomic. Pull requests must explain the behavior change, cite affected requirement IDs or ADRs, list verification commands, and call out generated CRD/OpenAPI changes. Include diagrams or screenshots only when visual behavior changes. Do not merge with failing checks.

## Security, Scope, and Evidence

Never commit credentials, kubeconfigs, Terraform state, or local runtime data. Preserve the ownership boundaries in `README.md`: ReleaseGuard complements Argo CD and Argo Rollouts; it does not replace them. Distinguish targets from measured results, local/CI evidence from cloud experiments, and planned features from implemented behavior.
