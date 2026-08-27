# ADR 0003: Represent Operational Mutations as ReleaseAction Resources

- Status: Accepted
- Date: 2026-08-26

## Context

Directly changing a Git-managed `ReleasePlan.spec` through REST would create drift, bypass review, and let Argo CD silently undo incident-response commands.

## Decision

Pause, promote, abort, and retry requests create short-lived `ReleaseAction` resources. Every action targets the exact ReleasePlan name, UID, and generation and carries an authenticated actor, reason, request time, and idempotency key. The REST API never changes Git-managed plan or policy specs.

Actions against stale identity are rejected. `Promote` cannot bypass a failed or inconclusive gate. Completed action specs are immutable audit records.

## Consequences

Operational intent is explicit, attributable, and reconcilable. API and CLI clients must resolve and display the target identity. Git remains the source of declarative rollout configuration.
