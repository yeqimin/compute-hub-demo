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
