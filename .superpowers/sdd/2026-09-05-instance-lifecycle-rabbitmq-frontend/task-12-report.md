# Task 12 实例控制台报告

## 完成范围

- 重构实例页为筛选、表格、详情抽屉三个组件；保留现有中文视觉体系并补充窄屏布局。
- 筛选支持关键字、状态、产品、集群、创建时间，以及仅平台管理员可见的租户；查询、重置和分页都持久化到 URL。
- 仅发送后端已实现的 `keyword`、`tenantId`、`productId`、`clusterId`、`status`、`startTime`、`endTime`、`page`、`size` 参数，未做伪造的本地全量筛选。
- 表格展示实例、算力配置、集群、费用、状态、创建时间；按 `allowedActions` 展示单项生命周期操作。
- 创建对话框默认 `SUCCESS`，高级演示场景折叠；实例创建与生命周期/批量写请求发送 `Idempotency-Key`，任务恢复遵循下文的 CAS/Inbox 契约。
- DELETE 在页面确认后还有第二层 `DELETE` 文案强确认；批量结果逐项呈现成功、跳过、失败及任务号。
- 详情抽屉展示配置、费用、引擎实例 ID、时间线、任务命令/重试/错误和审计记录；后端详情字段不足时显示 `-` 或空态。
- 使用 5 秒轮询和手动刷新；用户编辑筛选、创建/操作对话框或详情抽屉打开时暂停轮询，并在卸载时清理计时器。

## RED → GREEN

- 先新增 `Instances.spec.ts`。初始 4 项测试失败：页面不存在筛选 URL 同步、创建按钮、删除确认和批量结果 UI。
- 实现后新增轮询编辑保护与卸载清理测试。实例页测试 5 项通过。

## 验证

- `npm test`：7 files / 22 tests passed。
- `npm run typecheck`：通过。
- `npm run build`：通过、无 warning；实例异步块 `Instances-BVTa0jpY.js` 为 165.78 kB（gzip 47.82 kB），最大 JS 块为 `api-CKbYe2UA.js` 195.55 kB，均低于 500 kB。

## 风险

- 当前详情接口的公开字段可能只包含实例摘要；抽屉为任务、审计、计费明细预留了优雅降级展示，后端返回这些字段后即可直接呈现。
- 本任务按要求未接入 SSE 或 RabbitMQ；状态时效取决于 5 秒轮询和手动刷新。

## 审查修复：实例控制台契约对齐

### 修复范围

- `UNKNOWN` 实例的“发起人工对账”只在用户拥有 `instance:retry` 时显示，并调用 `POST /api/v1/tasks/{id}/reconcile`。该请求不再伪造 `Idempotency-Key`，也不再把对账误称为人工重试；显式重投保留给后续任务中心按 `UNKNOWN` 状态 CAS 处理。
- 平台管理员创建实例必须从既有 `/tenants` 数据中选择租户。租户加载中、加载失败和空列表均有明确状态；未选择租户会阻止提交并显示错误。租户管理员仍不需要也不会看到租户输入。
- 日期范围会在父级模型因路由返回、重置或外部导航变化时同步回日期控件，且只在值实际变化时更新本地范围，避免反向触发筛选写回。
- 生命周期与任务状态补齐中文标签；实例表格的原生详情按钮显式声明 `type="button"`。

### 测试覆盖

- 新增真实 `InstanceFilters`、`InstanceDetailDrawer`、`InstanceTable` 组件测试，覆盖日期模型同步、对账权限、真实表格 loading/empty 状态和操作权限门控。
- 页面集成测试覆盖浏览器导航后的日期 URL/请求一致性、平台管理员租户校验，以及对账抽屉调用真实 endpoint 且不发送重试幂等头。

### 幂等契约说明

创建实例以及生命周期/批量写入使用 `Idempotency-Key`。人工对账依赖服务端 Inbox 与结算幂等；显式 retry 由任务中心按任务状态执行 CAS，不承诺 HTTP 幂等键重放。

### 修复验证

- `npm test`：10 files / 29 tests passed。
- `npm run typecheck`：通过。
- `npm run build`：通过、无 warning；实例页 JS 167.28 kB（gzip 48.17 kB），最大 JS 块 195.55 kB，均低于 500 kB。
