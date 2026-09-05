# Task 11 前端基础报告

## 范围

- 按当前范围变更完成类型化 API、权限工具、Pinia 主题、分页查询、懒加载路由、任务中心菜单与 ECharts 模块加载。
- 未引入 RabbitMQ、SSE ticket、cursor 或 `useSseEvents`；任务中心保留 5 秒轮询与手动刷新。

## RED → GREEN

- RED：先新增权限、主题、分页查询三组测试。`instanceActions`、`ui`、`usePageQuery` 三个模块均不存在，Vitest 报三个无法解析的导入，符合预期。
- GREEN：实现最小模块后，三组测试 7 项通过；最后完整 `npm test` 为 4 个测试文件、9 项通过（包含原有状态工具测试）。

## 验证

- `npm run typecheck`：通过。
- `npm test`：4 files / 9 tests passed。
- `npm run build`：通过，无警告。最大的输出块为 `api` 192.56 KB；ECharts 最大拆分块为 101.87 KB，均低于 500 KB。
- 自动导入声明已生成并提交：`frontend/src/auto-imports.d.ts`、`frontend/src/components.d.ts`。

## 提交

- SHA：`afd4a09e8783eb64057751498d908e16b1ab06a7`
- 作者：`yeqimin <383988953@qq.com>`
- 信息：`refactor: add typed frontend foundation`

## 风险与后续

- 任务中心的列表字段按当前后端 `Page<AsyncTask>` 合同渲染；真实接口变更时应同步更新类型与页面。
- 本轮有意不恢复 SSE；刷新时效由轮询和手动刷新保证。
- ECharts 已按需拆分，但总览图表仍未添加窗口尺寸变化时的自适应 resize。

## 审查修复（Task 11）

- `usePageQuery` 以请求序号保护 `items`、`total`、`error` 与 `loading`；延迟 Promise 覆盖旧响应晚到、旧错误及最新请求仍加载的竞态。
- API 层为服务式 `ElMessage` 显式按需引入 message CSS；401 跳转通过可替换导航适配器处理，回归测试覆盖响应 envelope、`ApiError` 元数据与会话清理/跳转。
- 根级 `ElConfigProvider` 使用 Element Plus `zh-cn` locale，任务分页可保持中文标签；主题的 system 模式现在监听系统颜色变化，切换到显式主题时注销监听。
- 验证：`npm test` 为 6 files / 17 tests passed；`npm run typecheck` 与 `npm run build` 通过。构建没有超过 500 KB 的警告，最大 JS 块为 `api` 192.83 KB；`dist/assets/api-DYintPCv.css` 包含 `el-message` 样式。
- 风险：现有任务中心仍采用 5 秒轮询；本轮未新增 SSE、RabbitMQ 或后续页面。
