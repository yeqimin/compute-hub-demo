# Task 9 recovery-response stabilization

## Changes

- Reconciliation reports `settled: true` only when settlement freshly reaches
  `SUCCEEDED` or `FAILED`. Ignored, duplicate, and still-unknown results now
  return `settled: false` with the resulting task and instance state.
- Pagination calculates offsets as `long` and binds the mapper offset as
  `long`, avoiding signed `int` overflow for `page=Integer.MAX_VALUE`.
- Added regression coverage for callback-state mismatch, maximum page size,
  and legacy `CREATE_INSTANCE` outbox dispatch through `execute(CREATE)`.

## Verification

- Java 21 Docker focused build: `TaskRecoveryServiceTest` compiled; 13 tests
  discovered and skipped because the isolated test container cannot access the
  host Docker socket required by Testcontainers.
- Java 21 Docker full test: 105 tests, 0 failures, 0 errors, 63 skipped for
  the same Testcontainers socket limitation.

## Residual risk

The new database-backed regressions were compile-verified but not executed in
this environment because attaching the host Docker socket to the Maven
container was rejected by the safety policy. Run the focused test on a Java 21
host with Docker/Testcontainers access before release.
