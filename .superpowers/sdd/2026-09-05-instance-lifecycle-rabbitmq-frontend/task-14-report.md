# Task 14 report — compute operations dashboard

## Delivered

- `/api/v1/dashboard/summary` now returns GPU total/allocated/available, healthy and unhealthy nodes, running and abnormal instances, 24-hour task success rate and abnormal task count, available/frozen wallet balances, instance status distribution, and compact cluster/node/GPU topology.
- Tenant scope comes solely from the authenticated principal. The summary endpoint accepts no tenant parameter; platform administrators receive the all-tenant view and tenant users receive only their own wallet, tasks, instances, and used clusters.
- The dashboard uses five-second polling plus a manual refresh button. It has loading, empty, error and stale-data states; its utilization line is explicitly labelled as browser-session realtime sampling, not historical data.
- When the engine metrics call is unavailable, `/metrics/clusters` returns an empty collection rather than fabricated utilization values.
- Added Chinese responsive KPI cards, capacity/distribution charts, cluster health, compact topology, KPI/status navigation, ECharts module registration, and chart/observer/timer cleanup.

## Verification

- RED: `frontend/src/views/Dashboard.spec.ts` initially failed for the missing loading state, refresh control, and KPI navigation.
- GREEN: focused Dashboard frontend suite: 3 tests passed.
- Full frontend suite: 40 tests in 12 files passed.
- Frontend typecheck and production build passed; the largest emitted asset was 193.74 kB, with no chunk-size warning.
- Java 21 Docker focused unit suite: `DashboardServiceTest` passed 2 tests, 0 failures/errors/skips.

## Risk / follow-up

- The first controller-run MySQL/Testcontainers check failed because Java annotation SQL contained literal `&lt;&gt;`, which MyBatis sent unchanged to MySQL. The mapper was corrected to use native SQL `<>` in every Dashboard query.
- The corrected `DashboardMapperIntegrationTest,DashboardServiceTest` run used Java 21 and real MySQL 8.4: 3 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- The Spring scheduler logged connection errors while the temporary database was shutting down; assertions and Maven result were unaffected. Disabling schedulers in focused integration-test profiles remains a test-hygiene follow-up.
- The Java 21 Mockito run emitted its existing dynamic-agent/JDK warning. It does not affect the frontend warning-free build requirement.

## Review fix — suppress tenant physical trend samples (second pass)

- Dashboard trend sampling now accepts only finite `gpuUtilization` values whose `usageScope` is `PLATFORM_PHYSICAL`; suppressed tenant metric rows cannot append a fabricated `0%` point.
- Tenant logical views replace the physical GPU utilization line with the explicit empty-state explanation `共享物理利用率未按租户展示`. Platform views continue to render and sample the trend normally.
- Added frontend regression coverage for tenant no-sample behavior, platform finite-sample filtering, and the tenant explanation while preserving polling and existing error behavior.

## Second-pass verification

- Focused RED: the three new Dashboard tests failed on the pre-fix implementation (tenant trend remained mounted, suppressed rows were sampled as zero, and the explanation was absent).
- GREEN/full frontend suite: 12 test files, 44 tests passed.
- `npm run typecheck`: passed.
- `npm run build`: passed with no warning; largest emitted asset was 193.74 kB, below 500 kB.

## Commit

- Author: `yeqimin <383988953@qq.com>`
- Message: `fix: suppress tenant physical trend samples`
- SHA: `84180cac757c70333ef008645304eae01cb3e069` (implementation commit).

## Risk

- Tenant users intentionally receive no physical utilization trend; the empty state reflects the API privacy contract. Platform trend values still depend on the metrics endpoint returning finite numeric samples.

## Controller verification after tenant-isolation fix

- Reran `DashboardMapperIntegrationTest,DashboardServiceTest,DashboardControllerTest` with Java 21 and a real MySQL 8.4 Testcontainer.
- Result: 5 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- Scheduling was disabled for the focused mapper integration profile, so the earlier temporary-database shutdown noise did not recur.

## Review fix — tenant metric isolation

- Platform administrators retain engine-wide physical GPU/CPU/memory utilization. Tenant requests now return only clusters used by that tenant and label them `TENANT_SHARED_METRICS_SUPPRESSED`; shared physical utilization fields are omitted rather than presented as tenant values.
- Dashboard summaries identify their semantics with `resourceScope`: platform values are `PLATFORM_PHYSICAL`; tenant GPU figures are `TENANT_LOGICAL_USAGE` derived from that tenant's product requests and running instances. The UI labels this distinction and does not call tenant values physical capacity.
- Tenant topology rows return `gpuAllocated: null`; the UI explicitly says that shared-node allocated capacity is not shown. Platform topology preserves the actual node allocation.
- Added `DashboardControllerTest` (tenant cluster filtering/suppressed fields and platform physical fields), strengthened service and real-MySQL mapper assertions, and disabled scheduling in the mapper integration-test context to avoid shutdown noise.
- `HEALTHY` and compatible `READY` statuses now render as green “健康”; other topology statuses render Chinese labels. GPU capacity now observes container resize and disconnects/disposes on unmount. Abnormal task and instance cards no longer imply that `UNKNOWN` alone represents the aggregate.

## Review-fix verification

- RED: focused frontend test failed before the fix because the capacity chart lacked its own resize observer and topology displayed `HEALTHY`/a zero-like shared allocation; the pre-fix controller also returned all physical engine metrics to a tenant.
- GREEN: focused frontend dashboard suite: 4 tests passed; full frontend suite: 41 tests in 12 files passed; typecheck and production build passed.
- Java 21 Docker focused run compiled the dashboard tests and passed 4 service/controller tests with 0 failures/errors/skips. The real-MySQL mapper test was safely skipped because Testcontainers inside the Maven container cannot access the host Docker socket; mounting that socket was rejected as an unsafe privilege escalation. It must be rerun by the controller host that has direct Docker/Testcontainers access.
