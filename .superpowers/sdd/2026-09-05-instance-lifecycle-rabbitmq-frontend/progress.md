# SDD ledger — plan: docs/superpowers/plans/2026-09-05-instance-lifecycle-rabbitmq-frontend.md

Workspace: `/Users/xieqimin/Documents/chatGpt/算力调度平台/compute-hub-demo/.worktrees/instance-lifecycle-rabbitmq`
Branch: `codex/instance-lifecycle-rabbitmq`
Merge base: `e864d5c`
Spec: `docs/superpowers/specs/2026-09-05-instance-lifecycle-rabbitmq-frontend-design.md`

## Baseline

- Frontend: `npm test && npm run build` — 2 tests passed; typecheck and build passed; existing 2.2 MB chunk warning recorded for Task 11.
- Backend: Docker Java 21 `mvn test` with the Docker Desktop socket — 15 tests passed, 0 failures, 0 skipped, including the MySQL Testcontainers concurrency test.
- Local Java 8 is intentionally not used; all backend evidence must run with Java 21 in Docker.

## Preflight task self-consistency scan

| Task | Tests versus implementation | Files created versus later use | Finding |
|---|---|---|---|
| 1 | Migration assertions and enum compilation exercise the declared vocabulary | V3 and domain enums are consumed by Tasks 2, 4, 5, 7-10 | Consistent |
| 2 | State-machine tests enumerate legal, failure, timeout, and illegal transitions | State machine is consumed by lifecycle submission and settlement | Consistent |
| 3 | Contract and engine tests cover all operations, scenarios, and command reuse | Proto output is consumed by Tasks 5, 8, 9, and 16 | Consistent |
| 4 | Service tests cover tenant scope, idempotency, active-task conflict, and batch partial success | Lifecycle API and persistence contracts feed Tasks 5, 8-10, 12, and 16 | Consistent |
| 5 | Callback tests cover signatures, duplicate/conflicting callbacks, and operation-aware settlement | Settlement service feeds recovery, audit, SSE, and acceptance | Consistent |
| 6 | Topology test covers durable exchanges, quorum queue, retry TTLs, and DLQ | Compose and Spring AMQP config feed Tasks 7, 8, and 16 | Consistent |
| 7 | Publisher tests cover lease claim, confirm, return/NACK, and stale confirm | Outbox publish states feed Tasks 8, 9, 13, and 16 | Consistent |
| 8 | Consumer tests cover manual ACK, duplicate claim, retries, poison, and archive-before-ACK | Task/dead-letter states feed Tasks 9, 10, 13, and 16 | Consistent |
| 9 | Recovery tests cover retry, reconcile, tenant scope, redrive, and timeouts | APIs feed Task 13 and scripts in Task 16 | Consistent |
| 10 | SSE tests cover ticket scope, replay cursor, reconnect, and append-only audit | Typed realtime contracts feed Tasks 11-14 | Consistent |
| 11 | Frontend tests cover typed error handling, permission model, theme persistence, and reconnect fallback | Shared API/store/layout/routes feed Tasks 12-15 | Consistent |
| 12 | UI tests cover server filters, URL paging, action permission/state gates, batch results, and details | Instance UI feeds dashboard counts, billing links, and acceptance | Consistent |
| 13 | UI tests cover filters, six-stage timeline, retry/reconcile/redrive permissions, and SSE updates | Task UI feeds dashboard metrics and acceptance | Consistent |
| 14 | UI tests cover metric cards, charts, topology, and realtime deltas | Dashboard is verified by final production build and smoke flow | Consistent |
| 15 | CRUD tests cover product, tenant/user/role, billing filters, and trace-aware errors | Administration pages feed final acceptance and README | Consistent |
| 16 | End-to-end scripts and EXPLAIN evidence exercise all prior interfaces | Documentation and CI consume final commands without introducing runtime contracts | Consistent |

## Preflight shared-file and interface scan

| Producer task | Consumer task | Shared file or interface | Finding |
|---|---|---|---|
| 1 | 2 | `InstanceStatus`, `InstanceOperation`, `TaskState`, `TransitionPlan` | Names and states align with the spec |
| 1 | 4 | V3 instance/task columns and `LifecycleMapper` inputs | Active-task and actor/message fields are available before service work |
| 1 | 5 | V3 Inbox, order, task, and instance state fields | Settlement has every persistence field it needs |
| 1 | 7 | V3 Outbox lease, token, message, and task fields | Publisher schema precedes publisher implementation |
| 1 | 8 | `dead_letter_record` and task states | Poison/dead archive has durable storage before consumption |
| 1 | 9 | Task indexes, source task, manual retries, deadlines | Recovery queries have schema support |
| 1 | 10 | Audit and realtime tables | Append-only services have durable storage |
| 1 | 16 | V3 indexes and migrations | EXPLAIN may add V4, never rewrite V3 |
| 2 | 4 | `InstanceStateMachine.begin` | Submission uses one authoritative transition gate |
| 2 | 5 | `success`, `failure`, and timeout transitions | Callback convergence uses the same state model |
| 2 | 9 | UNKNOWN and reconciliation transitions | Recovery does not invent separate state rules |
| 2 | 12 | Backend state/action vocabulary | UI gates mirror server rules but server remains authoritative |
| 3 | 5 | `InstanceCommand` callback operation/result fields | Callback settlement can correlate operation and command |
| 3 | 8 | `ExecuteInstance` acceptance contract | Consumer calls the generic engine operation |
| 3 | 9 | `GetCommandStatus` | Reconciliation uses the persisted command identity |
| 3 | 16 | gRPC contract suite | Final verification covers all operation/scenario combinations |
| 4 | 5 | `LifecycleSubmission`, task snapshot, Outbox correlation | Settlement closes the exact task created in the transaction |
| 4 | 8 | `TaskClaim` and conditional task-state updates | Duplicate messages ACK without duplicate execution |
| 4 | 9 | Lifecycle controller, task ownership, and active task | Manual recovery reuses the original task rules |
| 4 | 10 | `AuditEntry` and `RealtimeEventService` call sites | Lifecycle writes emit append-only records transactionally |
| 4 | 12 | Lifecycle and batch REST APIs | Instance console consumes typed server contracts |
| 4 | 16 | Idempotency/concurrency acceptance | Final scripts verify one task and one Outbox per key |
| 5 | 9 | `SettlementService` and `TaskSnapshot` | Reconcile and late callback converge through one service |
| 5 | 10 | Settlement audit/realtime events | Callback outcomes appear in audit and SSE |
| 5 | 16 | Wallet/order/ledger invariants | Final acceptance verifies charge/unfreeze exactly once |
| 6 | 7 | Exchanges, routing keys, confirms, and returns | Publisher uses only declared durable topology |
| 6 | 8 | Main/retry/dead queues | Consumer retry TTLs are 2, 4, and 8 seconds |
| 6 | 16 | Compose service and health checks | Recovery scripts target the declared broker service |
| 7 | 8 | `EngineCommandMessage`, message ID, command ID, task ID | At-least-once duplicates are recognizable end to end |
| 7 | 9 | Outbox and task publication states | Recovery does not bypass publisher confirmation |
| 7 | 13 | Publish timestamps and IDs | Task timeline can render Outbox/RabbitMQ stages |
| 7 | 16 | Outbox lease query/index | Final EXPLAIN and restart tests cover lease recovery |
| 8 | 9 | DEAD/UNKNOWN task state and archived dead payload | Recovery and redrive operate on recorded facts |
| 8 | 10 | Consumer/dead-letter audit events | Operational failures are observable and append-only |
| 8 | 13 | Attempts, delay, error, message ID, and command ID | Task center exposes retry and poison details |
| 8 | 16 | Consumer/retry/DLQ recovery behavior | Final scripts cover broker and service restart |
| 9 | 10 | Recovery audit and realtime events | Manual actions and timeout transitions are streamed |
| 9 | 13 | Task list/detail/retry/reconcile/redrive APIs | Frontend actions retain server tenant and role checks |
| 9 | 16 | Recovery endpoints and timeout scheduler | Acceptance scripts use the public recovery flow |
| 10 | 11 | SSE ticket/stream/replay/error contracts | Typed frontend realtime client matches backend payloads |
| 10 | 12 | Instance event types and cursor | Instance list/details update without full refresh |
| 10 | 13 | Task event types and cursor | Task timeline advances from durable events |
| 10 | 14 | Cluster/summary event types | Dashboard updates incrementally with fallback polling |
| 11 | 12 | Typed API client, permission store, theme, and lazy routes | Instance UI uses the shared frontend foundation |
| 11 | 13 | Typed API client, SSE client, and permission helpers | Task UI uses the same error and realtime behavior |
| 11 | 14 | Lazy ECharts loading and theme tokens | Dashboard removes the baseline bundle warning |
| 11 | 15 | Form/table primitives and trace-aware errors | CRUD pages remain consistent with the shell |
| 11 | 16 | Vite build and chunk strategy | Final build checks the actual no-warning requirement |
| 12 | 13 | `AsyncTask`, audit, and timeline types | Details drawers share server DTO vocabulary |
| 12 | 14 | Instance state totals and realtime events | Dashboard figures reconcile with list state |
| 12 | 15 | Billing-to-instance navigation | Ledger rows open retained instance details including deleted rows |
| 12 | 16 | Full lifecycle UI/API behavior | Smoke test traverses create through logical delete |
| 13 | 14 | Task state totals and success rate | Dashboard task metrics match task-center semantics |
| 13 | 16 | Recovery and dead-letter actions | Acceptance verifies role-gated operations |
| 14 | 16 | Dashboard metrics and chunked build | Final verification covers visual data sources and production bundle |
| 15 | 16 | CRUD APIs/pages and README credentials | Final demo script covers administration and billing workflows |

Preflight result: no plan/spec contradiction found. No ruling required before Task 1.

## Task progress

- Task 1: review found two Important issues in commit `4deb736`: nondeterministic legacy Outbox-to-task backfill and missing V2-to-V3 legacy-data migration coverage.
- Task 1: minor (deferred): migration test uses floating `mysql:8`; final review must decide whether to pin the image or align the whole repository consistently.
- Task 1: Ruling: replace the plan-mandated instance-only legacy Outbox join with an exact join on both instance ID and the persisted `payload.commandId`; allow the later NOT NULL conversion to fail the migration if an old payload cannot be correlated rather than silently guessing a task — spec requires exact task linkage and MySQL truth — cost if wrong: a nonconforming legacy payload will block upgrade and require explicit data repair instead of automatic migration.
- Task 1: fix round 1/5 (2 addressed, 0 open — exact Outbox correlation and V2-to-V3 migration coverage; commits `4deb736..e66f69a`).
- Task 1: complete (commits `e864d5c..e66f69a`, review clean; one deferred Minor about floating MySQL test image).
- Task 2: review found one Important test gap: delete transitions from `STOPPED` and `FAILED` lacked success/failure regression rows.
- Task 2: fix round 1/5 (1 addressed, 0 open — all four legal delete source states now covered; commits `170ebb9..f21f528`).
- Task 2: complete (commits `e66f69a..f21f528`, review clean).
- Task 3: Ruling: add `V4__mock_engine_lifecycle.sql` even though Task 3's file list omitted migrations, because spec section 5.5 requires durable operation, tenant, result message, and execution time for restart-safe `GetCommandStatus`; do not encode these facts into unrelated legacy columns or rewrite V3 — cost if wrong: one extra migration version is consumed and later index evidence must use V5 rather than V4.
- Task 3: review found one Critical and two Important issues: completed callbacks were replayed on every restart, accepted-work recovery lacked a durable claim, the reconstruction test skipped actual startup recovery, and outbound HMAC/body secrecy lacked a contract test.
- Task 3: Ruling: recover only newly `ACCEPTED` or lease-expired `PROCESSING` commands through an atomic MySQL status claim using existing `updated_at`; never automatically replay completed callbacks — the business timeout/reconcile path reads durable `GetCommandStatus` facts — cost if wrong: a crash after engine completion is persisted but before callback send delays convergence until the business timeout/reconciliation path rather than immediate engine-startup redelivery.
- Task 3: fix round 1/5 (3 original findings addressed, 1 new test gap open — durable claim/recovery and outbound HMAC contract fixed; commits `9c3cbfd..8213489`).
- Task 3: fix round 2/5 (1 addressed, 0 open — duplicate public submission across repository reconstruction restored; commits `8213489..ceff3bd`).
- Task 3: complete (commits `f21f528..ceff3bd`, review clean).
- Task 3: Ruling: add `business-server/src/main/resources/db/migration/V4__mock_engine_lifecycle.sql` with the design-required `mock_engine_command` lifecycle/result columns even though the Task 3 file list omitted migrations — the spec explicitly requires persistent operation/status recovery and encoding values into unrelated legacy columns would corrupt the data model — cost if wrong: Task 16 must use V5 rather than V4 for any EXPLAIN-driven index additions.
- Task 4: review found one Critical and two Important issues in `fb5e0e7`: batch idempotency can fall through while MySQL says PROCESSING, the legacy worker joins tasks by instance instead of authoritative outbox task ID, and malformed enum payloads are returned as 500.
- Task 4: Ruling: add `V5__idempotency_processing_lease.sql` with nullable batch ownership token and lease timestamp, plus token-conditional renew/complete and stale takeover only for the same fingerprint; never let a non-owner execute or overwrite the first response — MySQL must remain authoritative when Redis is unavailable — cost if wrong: long batches require lease renewal and Task 16 must use V6 for any later index migration.
- Task 4: fix round 1/5 (3 addressed, 0 open — MySQL-authoritative batch ownership, exact legacy task linkage, and stable client-error mapping; commits `fb5e0e7..f1b2a39`).
- Task 4: complete (commits `ceff3bd..f1b2a39`, review clean).
- Scope change (user, 2026-09-05): RabbitMQ has not been introduced and must not be added in this iteration. Skip Tasks 6-8 and retain the existing database Outbox + Worker + gRPC delivery path; adapt Tasks 9, 13, and 16 so recovery, task-center visibility, tests, Compose, and documentation describe the non-RabbitMQ architecture.
- Scope change (user, 2026-09-05): prioritize complete, polished frontend functionality. Keep Task 5; reduce Task 9 to task list/detail/manual retry/timeout reconciliation; skip durable SSE in Task 10 and use polling plus manual refresh; keep the frontend foundation and Tasks 12-15; reduce Task 16 to Docker startup, core smoke coverage, README, and demo script, with broker recovery and exhaustive SQL evidence deferred.
- Task 5: review found four Important issues and one Minor test gap in `a821094`: recovery still correlated Outbox by instance, UNKNOWN recovery used creation-only DISPATCHING state, reconciliation trusted an engine response without exact identity checks, and callbacks could overwrite `engine_instance_id`.
- Task 5: fix round 1/5 (4 Important addressed; exact task Outbox recovery, operation-specific timeout/retry states, reconciliation identity checks, engine instance identity constraints; one new Important NOT_FOUND recovery edge remained; commits `a821094..c7213f8`).
- Task 5: fix round 2/5 (1 Important addressed — NOT_FOUND + unspecified operation now permits only the authorized UNKNOWN task to requeue while command ID remains strict; commits `c7213f8..45a29bd`).
- Task 5: complete (commits `f1b2a39..45a29bd`, review clean; deferred Minor: signed HTTP callback DTO has signer/service coverage but not a full controller transport test).
- Tasks 6-8: skipped by user scope change; RabbitMQ remains a future enhancement.
- Task 9: review found two Important issues in `50a7f2e`: reconcile could report settled when settlement ignored a conflicting engine fact, and extreme page values could overflow the SQL offset; one Minor legacy CREATE dispatch test gap.
- Task 9: fix round 1/5 (2 Important and 1 Minor addressed — truthful reconciliation response, long offset, and legacy CREATE execute compatibility; commits `50a7f2e..fa30dc4`).
- Task 9: controller verification reran 46 real MySQL/Testcontainers tests with 0 skipped, failures, or errors; misleading root-level skipped-test report removed in `cb75ed5` while the authoritative SDD report was retained.
- Task 9: complete (commits `45a29bd..cb75ed5`, review clean).
- Task 10: durable SSE skipped by user scope change; frontend uses polling plus manual refresh in this iteration.
- Task 11: review found three Important and two Minor issues in `afd4a09`: stale polling responses could overwrite newer page state, imperative messages lacked CSS, pagination lost Chinese locale, system theme did not observe OS changes, and shared API error behavior lacked regression tests.
- Task 11: fix round 1/5 (all five addressed — latest-request query semantics, scoped message CSS, zh-cn provider, system theme listener, and typed API/401 tests; commits `d12e048..ce3cf0c`).
- Task 11: complete (commits `cb75ed5..ce3cf0c`, review clean; 17 frontend tests and warning-free production build).
- Task 12: Ruling: do not pretend task recovery supports `Idempotency-Key`; the original acceptance requires the key for instance creation and wallet recharge, while task retry is protected by an exact-task UNKNOWN-state CAS and reconciliation by Inbox/settlement idempotency. The instance drawer's “人工对账” action must call `/reconcile` with `instance:retry` visibility; explicit redispatch remains in the task-center UI — cost if wrong: duplicate manual retry requests return a stable state conflict rather than replaying the first HTTP response.
- Task 12: review found five Important and two Minor issues in `e1bb9b0`: reconciliation permission/endpoint mismatch, missing platform-admin tenant validation, stale date-range UI, a misleading task-retry idempotency claim, over-stubbed tests, incomplete Chinese statuses, and a native button type omission.
- Task 12: fix round 1/5 (all Important and Minor implementation issues addressed — correct reconcile contract, platform tenant selection, date synchronization, real component tests, localized statuses, and safe button type; commits `e1bb9b0..0ba7a4f`).
- Task 12: complete (commits `ce3cf0c..6d10a63`, review clean; deferred Minor: platform tenant loading/failure UI branches are implemented but do not each have a dedicated regression test).
- Task 13: review found three Important and two Minor issues in `5d63f78`: task date controls did not restore/reset with URL state, the abnormal metric omitted FAILED/DEAD, list times were raw strings, retry deadlines were not visible, and tests hid those gaps.
- Task 13: fix round 1/5 (all addressed — bidirectional date state, honest abnormal metric, localized times, retry/deadline observability, and regression coverage; commits `5d63f78..4199d24`).
- Task 13: complete (commits `6d10a63..4199d24`, review clean; 37 frontend tests and warning-free production build).
- Task 14: Ruling: physical engine utilization and shared-node allocated capacity cannot be attributed to one tenant with the current schema, so tenant-scoped dashboard responses must filter to authorized clusters and suppress or replace shared physical figures with explicitly tenant-derived usage; never expose the platform-wide engine values as if they were tenant metrics — cost if wrong: tenant dashboards show less infrastructure detail until node placement or quota ownership is modeled.
- Task 14: controller RED verification found literal XML escapes in Java annotation SQL after `93e6d20`; fixed native SQL operators in `2e0bff4` and verified real MySQL.
- Task 14: review found three Important and one Minor issue: tenant metrics/topology leaked shared physical utilization, HEALTHY clusters rendered as warnings, the capacity chart lacked resize lifecycle, and abnormal KPI navigation implied UNKNOWN-only scope.
- Task 14: fix round 1/5 (all original findings addressed — suppressed tenant physical metrics, tenant logical scope, healthy localization, resize cleanup, and honest KPI navigation; commits `2e0bff4..fb120d4`; controller verified mapper/service/controller 5/5 on real MySQL with 0 skipped).
- Task 14: fix round 2/5 (one new Important addressed — tenant-suppressed metrics no longer create fake 0% trend samples; commits `ac16da1..84180ca`).
- Task 14: complete (commits `4199d24..365862e`, review clean; 44 frontend tests, 5 focused backend tests, warning-free build).
- Task 15: controller verification found a duplicate `CatalogService.roles()` definition, an invalid test expectation that allowed a tenant administrator to remove their own management role, and a null-tenant platform-user boundary returning 400 instead of 403; all were corrected without weakening RBAC.
- Task 15: the deferred management-page URL query requirement was completed for products, access, billing, and resources, including refresh/browser-navigation restoration and selected-cluster persistence.
- Task 15: controller verification passed 2/2 `AdministrationSecurityTest` cases on Java 21 with real MySQL 8.4 Testcontainers, plus 50/50 frontend tests and a warning-free production build.
