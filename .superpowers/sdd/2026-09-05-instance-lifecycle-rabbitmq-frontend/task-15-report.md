# Task 15 管理工作流报告

## 提交

- 功能提交：`63c8c4d3e93100deef2f3b258289fcfb4e391d67`（`feat: complete administration workflows`）
- 作者：`yeqimin <383988953@qq.com>`

## 实际实现

- 产品：服务端 `keyword`、`enabled`、分页与固定 ID 排序；平台管理员可新增、编辑、确认启停。SKU 只允许创建时提供，更新 DTO 不含 SKU。前后端均校验 GPU/CPU/内存/分价为正数。
- 访问：租户、用户服务端筛选分页；平台管理员全局可见，租户管理员由服务层固定到当前租户。支持角色代码分配、用户启停与角色权限详情。租户管理员不能授予平台管理员、修改其他租户或移除自己的租户管理员角色；同时阻止当前管理员自停用和最后一个平台管理员被停用/移除。
- 账务：钱包、租户选择、以元输入并用 `BigInt` 精确换算分、`Idempotency-Key` 充值确认、类型/业务号/时间筛选及真实服务端分页。流水查询只读，账务行在可关联订单/实例时跳转实例页。
- 资源中心：只读集群/节点筛选、健康状态、GPU 容量与利用率、节点详情、加载/空/错/手动刷新状态。
- 路由状态：产品、用户、账务和资源页面均从 URL 恢复筛选、分页与选中集群，搜索、重置、分页和选择操作同步回 URL，支持刷新和浏览器导航恢复。
- 兼容：实例和任务页改为适配产品/租户的新分页契约。

## RED / GREEN

- RED：`npm test -- src/views/AdminCrud.spec.ts` 初次失败，原因是产品页面未发送 `keyword`、`page`、`size`。
- URL RED：管理页面从带筛选参数的地址启动时仍请求默认条件；新增四类页面的路由恢复回归用例后稳定复现。
- GREEN：产品查询、viewer 无变更按钮、四类管理页 URL 恢复与回写测试通过；前端全量为 13 文件、50 测试通过。
- 后端 RED：首次 Java 21 编译暴露 `CatalogService.roles()` 重复定义；修正后的真实 MySQL 运行又暴露测试错把“移除自己的租户管理员角色”期望为成功，以及平台用户 `tenant_id=NULL` 被误映射为 400。
- 后端 GREEN：保留“不能移除自己管理角色”的 409 保护，将跨租户/平台用户修改统一为 403；`AdministrationSecurityTest` 在 Java 21 + MySQL 8.4 Testcontainers 下 2/2 通过，无失败、错误或跳过。

## 验证

- `npm test`：13 files / 50 tests passed。
- `npm run build`：通过（包含 TypeScript typecheck 与 Vite production build）。
- `mvn -pl business-server -am -Dtest=AdministrationSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test`：2 tests passed，Java 21 + 真实 MySQL Testcontainers。
- `git diff --check`：通过。

## 未实现与风险

- 现有集群/节点数据模型没有租户配额或节点归属；资源中心保持既有的只读共享基础设施视图，不能诚实实现逐租户物理资源隔离。Task 14 已对仪表盘做了租户物理指标抑制。
- 管理页各自保留显式的路由序列化代码，以便展示每个页面的可共享查询契约；如果继续扩展筛选页，可再抽取通用路由查询组合式函数。
