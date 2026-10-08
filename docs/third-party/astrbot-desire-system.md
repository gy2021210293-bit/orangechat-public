# AstrBot Desire System 上游固化与映射说明

## 1. 上游来源与固定版本

- **开源仓库**: [Yussica1026/AstrBot-Desire-System-](https://github.com/Yussica1026/AstrBot-Desire-System-)
- **开源许可证**: MIT License
- **固定 Commit SHA**: `43323b3654e1fc929880326c506cd1d6fc70bcbe`
- **固化日期**: 2026-09-11
- **本地代码备份路径**: `D:\Code\AstrBot-Desire-System-`

后续若需升级上游，必须基于该 Commit 进行逐行 Diff 比对与评估，严禁直接拉取 main 分支覆盖。

---

## 2. 模块与文件映射表

| 上游文件 (Python) | 客户端对应文件 (Kotlin) | 职责与差异处理 |
| --- | --- | --- |
| `desire/core.py` | `app/.../data/ai/desire/DesireModels.kt`<br>`app/.../data/ai/desire/DesireEventEngine.kt` | 纯 Kotlin 实现九维驱动模型、事件变动定义、惊喜系数计算。保持原数值和边界。 |
| `desire/tick.py` | `app/.../data/ai/desire/DesireTickEngine.kt` | 耦合矩阵传导、自然回归/增长、动态心跳间隔计算、固定 8 步 Tick 契约。 |
| `desire/thoughts.py` | `app/.../data/ai/desire/DesireThoughtEngine.kt` | 念头模板库、抑制规则、概率生成、命中强化、3次转执念（上限3条）、消退与解决。 |
| `desire/safety.py` | `app/.../data/ai/desire/DesireSafety.kt` | 基线漂移监测（>20 告警，>30 强制回归）、情绪风暴阻尼、疲劳闸门。 |
| `desire/monologue.py` | `app/.../data/ai/desire/DesireMonologue.kt` | 纯模板制内心独白生成（不消耗 LLM Token）。 |
| `desire/active_send.py` | `app/.../data/ai/desire/DesireActiveSendPolicy.kt`<br>`app/.../data/service/ProactiveMessageService.kt` | 冷却 8 小时、每日上限 3 条、离开时间判定、空话头拦截与回退模板。接入系统主动消息链路。 |
| `desire/integration.py` | `app/.../data/ai/desire/DesireRepository.kt`<br>`app/.../data/ai/desire/db/DesireDao.kt` | 单实例 SQLite 替换为 Android Room 事务层，按 AssistantId 隔离存储状态、日志、主动发送记录与事件收据。 |
| `mcp_server.py` | `app/.../data/ai/desire/DesireTools.kt` | 映射 `desire_status`, `desire_event`, `desire_tick`, `desire_resolve_thought` 4 个原生 AI 工具。 |

---

## 3. Android 平台差异与约束

1. **多 Assistant 隔离**：上游设计为单人 Bot，OrangeChat 将所有状态、日志、念头与任务以 `assistant_id` 严格隔离。
2. **Room 事务与幂等**：利用 `desire_receipts` 表配合数据库事务，防止 AlarmManager 与 WorkManager 并发或网络重试导致数值多次计算。
3. **心跳机制**：不使用无休止的常驻后台线程，改为 `AlarmManager` 动态闹钟 + `WorkManager` 系统保底调度（15–45 分钟），并在应用启动或唤醒时根据时间戳进行无漂移的受控延迟补算（24h 内最多 96 步）。
4. **行为非入侵**：Desire 系统仅当助手显式开启 `desireSystemEnabled = true` 时生效；聊天注入精简状态，不污染长期记忆库与 Self 证据链。
