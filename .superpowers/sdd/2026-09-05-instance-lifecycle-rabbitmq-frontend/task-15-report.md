# Task 15 管理工作流报告

## 提交

- 功能提交：`63c8c4d3e93100deef2f3b258289fcfb4e391d67`（`feat: complete administration workflows`）
- 作者：`yeqimin <383988953@qq.com>`

## 实际实现

- 产品：服务端 `keyword`、`enabled`、分页与固定 ID 排序；平台管理员可新增、编辑、确认启停。SKU 只允许创建时提供，更新 DTO 不含 SKU。前后端均校验 GPU/CPU/内存/分价为正数。
- 访问：租户、用户服务端筛选分页；平台管理员全局可见，租户管理员由服务层固定到当前租户。支持角色代码分配、用户启停与角色权限详情。租户管理员不能授予平台管理员、修改其他租户或移除自己的租户管理员角色；同时阻止当前管理员自停用和最后一个平台管理员被停用/移除。
- 账务：钱包、租户选择、以元输入并用 `BigInt` 精确换算分、`Idempotency-Key` 充值确认、类型/业务号/时间筛选及真实服务端分页。流水查询只读，账务行在可关联订单/实例时跳转实例页。
- 资源中心：只读集群/节点筛选、健康状态、GPU 容量与利用率、节点详情、加载/空/错/手动刷新状态。
- 兼容：实例和任务页改为适配产品/租户的新分页契约。

## RED / GREEN

- RED：`npm test -- src/views/AdminCrud.spec.ts` 初次失败，原因是产品页面未发送 `keyword`、`page`、`size`。
- GREEN：新增产品查询和 viewer 无变更按钮测试通过；前端全量为 13 文件、46 测试通过。
- 后端：新增 `AdministrationSecurityTest`，覆盖产品分页、viewer 三类变更 403、租户管理员角色越权。Docker Java 21 的运行在宿主 Docker socket 权限被拒后，提权请求又因挂载 Docker socket 的高风险策略拒绝，故没有可声明的后端 GREEN 证据。

## 验证

- `npm test`：13 files / 46 tests passed。
- `npm run build`：通过（包含 TypeScript typecheck 与 Vite production build）。
- `git diff --check`：通过。

## 未实现与风险

- 现有集群/节点数据模型没有租户配额或节点归属；资源中心保持既有的只读共享基础设施视图，不能诚实实现逐租户物理资源隔离。Task 14 已对仪表盘做了租户物理指标抑制。
- 管理页目前未将每项筛选状态回写 URL query；实例和任务页已有该能力。需要后续统一路由查询组合式函数才能完整覆盖这一 UX 要求。
- 无法在本机取得 Java 21 + MySQL Testcontainers 运行结果；上线前必须在具备 Docker socket 授权的 Java 21 环境执行 `AdministrationSecurityTest` 和完整 Maven 测试。
