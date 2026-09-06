# Task 16 项目交付与验证报告

## 实际实现

- Docker Compose：MySQL、Redis、业务服务、Mock Engine 和前端一键构建启动；启动命令等待健康状态，Mock Engine 增加进程健康检查和有限失败重启。
- 启动修复：为 Mock Engine 执行器与回调客户端明确 Spring 构造函数注入入口，修复正式容器因多个构造函数无法装配而退出的问题。
- 自动验证：抽取统一 HTTP 客户端和健康轮询；覆盖基础冒烟、完整实例生命周期、失败解冻、重复回调防重、超时对账、RBAC、租户隔离、50 并发幂等和引擎重启恢复。
- 交付命令：Makefile 增加状态、生命周期与整体验证目标；持续集成增加 Docker 全链路任务，失败时保存 Compose 日志并清理数据卷。
- 文档：重写快速启动、功能范围、核心流程、一致性安全和扩展说明；同步实例状态机、Outbox + gRPC 架构和 API 分组。

## RED / GREEN

- RED：当前分支镜像启动后，成功场景最终进入 `UNKNOWN`；容器日志显示 Mock Engine 因多个构造函数且没有明确注入入口而启动失败。
- GREEN：为两个存在测试辅助构造函数的 Spring 组件标注正式注入构造函数；重建后 Mock Engine 正常运行，完整创建、停止、启动、重启和删除链路通过。
- 脚本修正：超时恢复从错误的 `/retry` 改为 `/reconcile`；恢复脚本从旧状态 `DISPATCHING` 更新为当前状态 `CREATING`；基础冒烟去除固定等待并按健康和实例状态轮询。

## 验证结果

- Java 21 + MySQL/Redis Testcontainers：业务服务 110 项、Mock Engine 17 项，共 127 项测试通过，0 失败、0 错误、0 跳过。
- Vitest：13 个测试文件、50 项测试通过。
- TypeScript 类型检查与 Vite 生产构建：通过，无大包警告。
- Docker 全链路：基础冒烟、完整生命周期、异常结算与权限验收、50 并发幂等、Mock Engine 重启恢复全部通过。
- 配置与文档：Node 语法、Shell 语法、Compose 配置、`git diff --check` 通过；交付文档已清理指定措辞。

## 当前边界

- 当前可靠投递使用数据库 Outbox Worker + gRPC，不包含消息队列。
- 前端实时性采用轮询和手动刷新，不包含持久化 SSE。
- Kubernetes、真实云厂商适配、按分钟计费和生产级高可用保留为扩展方向。
