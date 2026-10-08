# AstrBot Desire System Android 本地移植计划

> 状态：实施前方案，不包含代码改动  
> 日期：2026-09-11  
> 目标：仅在手机本地运行  
> 上游：Yussica1026/AstrBot-Desire-System-  
> 固定版本：43323b3654e1fc929880326c506cd1d6fc70bcbe（MIT）

## 0. 方案结论

第一版以 AstrBot Desire System 为唯一行为母实现，不再自行叠加 Affect、Need、E 信号，也不混入 WrenWen、Drivesoid 或 EmoAI 的运行代码。

完整保留 AstrBot 的九维驱动力、事件影响、自然增长与衰减、驱动力耦合、基线漂移、念头强化与消退、执念反向喂养、疲劳门控、动态心跳，以及因为想念、开心、低落或久未联系而主动找用户的行为。

只做四类 Android 必要替换：

1. Python 改为 Kotlin；
2. JSON 文件改为 Room；
3. AstrBot 定时任务改为 AlarmManager + WorkManager；
4. 单实例改为按 Assistant 隔离，并补充移动端重试所需的持久化幂等。

移植期间不调整阈值、不关闭执念反馈、不禁止深夜主动消息、不把陪伴行为改成工作提醒。先做到行为一致，再根据真实手机数据单独调参。

## 1. 开工前提

当前工作树已有未提交的 Memory V1、Self、主动消息与 Room 迁移修改，实施时不得清理或覆盖。

- 先完成并冻结当前 Self v39：真实经历 → REVIEW self → 多次独立证据 → ACTIVE self。
- 确认 schema 39 与 Migration_38_39 稳定后，Desire 使用 v40。
- 冻结现有主动消息生成、落库、通知和跳转契约。
- Desire 只能调用现有发送链路，不新建第二套发送器。
- 新增第三方来源文档，固定上游 SHA、许可证、文件映射及 Android 差异。
- 后续升级上游必须按 commit 比对，禁止直接跟随 main。

## 2. 必须复刻的上游行为

### 2.1 九维驱动力

上游注释虽称“八维”，实际代码包含九维，以代码为准：

| 驱动力 | 初始/基线 | 增长 | 衰减 | 阈值 |
| --- | ---: | ---: | ---: | ---: |
| attachment | 60 | 0.20 | 0.10 | 70 |
| curiosity | 40 | 0.20 | 0.10 | 60 |
| reflection | 30 | 0.15 | 0.20 | 50 |
| duty | 40 | 0.10 | 0.20 | 70 |
| social | 30 | 0.10 | 0.15 | 60 |
| fatigue | 20 | 0.00 | 0.30 | 80 |
| intimacy | 40 | 0.10 | 0.15 | 70 |
| stress | 20 | 0.00 | 0.20 | 80 |
| joy | 50 | 0.00 | 0.15 | 80 |

保留 0–100 边界、随机扰动、基线更新和原始浮点语义。

### 2.2 事件全集

完整迁移：

- wife_message
- wife_silent
- task_done
- penpal_message
- diary_written
- fight
- reconcile
- intimacy_done
- heavy_work
- rest
- discovery
- happy_moment
- creative_done

上游只有目标用户真实发言会自动触发 wife_message，其余事件主要由 desire_event 工具显式提交。第一版不增加“让模型暗中判断每条消息是否吵架/和好”的分类器。

### 2.3 Tick 固定顺序

1. 自然增长或向基线衰减；
2. 驱动力耦合；
3. 基线 EMA / anchor 更新；
4. 执念反向喂养驱动力；
5. 生成行为提示；
6. 疲劳门控；
7. 计算下一次 15–45 分钟动态间隔；
8. 持久化状态和日志。

执行顺序是兼容契约，不能交换。

### 2.4 念头与执念

完整迁移候选模板、生成概率、加权选择、重复命中强化、连续三次转执念、最多三条、72 小时寿命、抑制、自然消退、手动解决及执念反向影响驱动力。

### 2.5 主动唤醒

保持上游阈值与优先级：

- 用户最近 3 小时出现则不发送；
- 冷却 8 小时；
- 每日最多 3 条；
- 15 小时未联系触发 long_absent；
- attachment ≥ 88：想念；
- joy ≥ 90：分享开心；
- stress ≥ 85 或 fatigue ≥ 88：表达低落；
- 模型生成 1–3 句话；
- 禁止空洞开场，无效时使用上游模板回退；
- 保留深夜可主动联系的私人陪伴语义。

## 3. Room v40 数据设计

### 3.1 DesireStateEntity

每个 Assistant 一行：

- assistant_id（主键）
- drives_json（当前值、基线、参数）
- thoughts_json（念头与执念）
- last_tick_at
- tick_count
- next_tick_at
- schema_version
- updated_at

使用版本化 JSON 保存高度耦合的上游状态，避免九维被拆成大量难升级的列。

### 3.2 DesireTickLogEntity

用于诊断和复现，不参与记忆召回：

- id、assistant_id、created_at、tick_number
- event_name
- drive_changes_json
- thought_changes_json
- behavior_hints_json
- monologue
- warnings_json

每个 Assistant 只保留最近 500 条，事务内定额裁剪。

### 3.3 DesireActiveSendEntity

只记录真正成功写入 Conversation 的主动消息：

- id、assistant_id、sent_at、reason、content
- drives_snapshot_json
- conversation_id、message_id

[PASS]、空内容、生成失败、任务启动均不计入冷却和每日额度。

### 3.4 DesireEventReceiptEntity

用于解决 Alarm、Worker、工具调用和进程恢复的重复投递：

- event_id（主键）
- assistant_id、event_name
- source_type、source_id
- created_at、processed_at

真实消息 eventId 由 assistantId + conversationId + messageId + wife_message 组成；工具优先用 toolCallId；Tick 使用计划时间槽。相同 ID 不得二次改变状态。

### 3.5 DAO、事务和迁移

至少提供 getOrCreateState、applyEventOnce、runTickOnce、listRecentLogs、getLastSuccessfulSend、countSuccessfulSendsSince、deleteAllForAssistant。

收据、状态和日志必须同事务提交。数据库升级 39→40，新增 Migration_39_40、schema 40、39→40 测试和历史 30→40 全链测试；最后必须做旧 APK 数据库到新 APK 的真机升级验证。

## 4. Kotlin 核心模块

建议目录：

app/src/main/java/me/rerere/rikkahub/data/ai/desire/

| 上游文件 | Kotlin 对应 |
| --- | --- |
| desire/core.py | DesireModels.kt、DesireEventEngine.kt |
| desire/tick.py | DesireTickEngine.kt |
| desire/thoughts.py | DesireThoughtEngine.kt |
| desire/safety.py | DesireSafety.kt |
| desire/monologue.py | DesireMonologue.kt |
| desire/integration.py | DesireRepository.kt、DesireService.kt |
| desire/active_send.py | DesireActiveSendPolicy.kt |

核心层必须是纯 Kotlin：

- 不依赖 Compose、Room、WorkManager 或 Android Context；
- 时间、随机源和时区全部注入；
- 输入旧状态，输出新状态与 effect，不直接写库；
- 随机抽样调用顺序保持上游一致；
- Python/Kotlin随机实现不同，黄金测试使用可记录序列 RandomSource，而非只比较相同 seed。

## 5. 事件与 AI 工具

### 5.1 自动 wife_message

真实用户消息成功持久化后：

1. 检查当前 Assistant 是否启用 Desire；
2. 构造稳定 eventId；
3. 事务性应用一次 wife_message；
4. 重新安排下一 Tick；
5. 失败只记诊断，不阻塞正常回答。

SYSTEM、TOOL、AI 消息、插件上下文、动态环境和记忆注入不得误算为 wife_message。

### 5.2 完整迁移四个工具

- desire_status
- desire_event
- desire_tick
- desire_resolve_thought

工具通过现有统一注册面暴露，自动绑定当前 Assistant，不允许模型指定其他 Assistant；使用 toolCallId 幂等；只有启用后可调用；参数、事件枚举和返回语义尽量与上游一致。

## 6. 手机本地动态心跳

- 每个 Assistant 使用唯一任务名 desire_tick_<assistantId>。
- 每次 Tick 后按上游公式安排下一次一次性任务。
- AlarmManager 提高及时性，WorkManager 负责可靠执行和系统恢复。
- 两条入口使用同一时间槽 eventId，由 receipt 去重。
- 开机、应用升级、时区变化和开关变化时重新调度。
- 不为 15 分钟心跳常驻前台服务；Android 后台时间是近似检查时间，不承诺硬实时。

### 延迟补算

- 根据 lastTickAt 与计划间隔计算遗漏步数；
- 24 小时内最多精确补算 96 次；
- 更早部分先做确定性批量向基线演化，再执行有限 Tick；
- 补算仍保持上游执行顺序；
- 同一 Assistant 串行处理，不能让并发 Worker 交错。

### 用户在场定义

只使用该 Assistant 会话中最后一条真实 USER 消息时间，不使用应用启动时间、AI 主动消息、工具/系统消息或其他 Assistant 的消息。

## 7. 主动唤醒接入

唯一链路：

Desire Worker → Tick / shouldSend → ProactiveMessageTriggerService → 现有生成、落库、通知和跳转。

新增 assistantId、desireReason、desireContext、desireEventId 等触发参数。TriggerService 在生成前二次检查开关、用户在场、冷却、每日上限及事件是否已发送，防止竞态。

生成时临时注入 desire_context，包含触发原因、自然语言内心状态、相关念头和行为限制；不得暴露系统、数据库、阈值和裸分数。

- 保留 1–3 句话和禁止空洞开场；
- [PASS]/空内容/失败不记账；
- 无效内容使用对应上游模板回退；
- 只有消息成功持久化后才记录成功发送；
- 模板回退可单独关闭，默认开启。

## 8. 普通聊天状态注入

Desire 不是记忆库。每轮只生成精简 desire_state：

- 偏离基线最明显的 1–3 项；
- 当前最相关的一条念头或执念；
- 一句自然语言行为倾向；
- 不包含裸九维数组或内部工具说明。

建议注入顺序：

1.外部记忆
2.最近聊天
3.动态环境
4.插件上下文
5.stable_self
6.本地长期记忆
7.desire_state
8.当前用户消息

为 desire_state 增加独立 metadata，并测试同角色消息合并、重复注入和顺序。Tick 日志、独白与 Desire State 不能直接成为 Fact、Episode 或 Self 证据；只有真实发出的消息和用户后续反应可以进入现有证据链。

## 9. 每日日记

### 9.1 增加显式只读查询

新增：

memory_search_daily_notes(query?, date_from?, date_to?, limit=3)

规则：

- 只查询当前 Assistant；
- 支持单日、日期区间和关键词；
- 第一版对 summary、emotional_thread 使用 Room LIKE，limit 为 1–5；
- 返回日期、摘要和情绪脉络；
- 只在模型明确需要回看时调用，不参加每轮自动召回。

### 9.2 废弃 unfinished_topics 的行为作用

“话题有没有自然结束”不再驱动提醒、询问、Desire、Self 或主动唤醒。

兼容处理：

- v40 不删除旧字段；
- 夜间日记提示不再要求 unfinished_topics；
- 新记录写空数组；
- 移除相关必填/第一人称校验；
- UI、召回、Desire、Self 均不消费；
- 旧值仅作为历史兼容数据保留。

## 10. Assistant 设置和 UI

Assistant 新增：

- desireSystemEnabled = false
- desireActiveSendEnabled = true
- desireFallbackTemplateEnabled = true

总开关默认关闭，避免升级后旧 Assistant 突然主动联系。入口：

当前 AI → 记忆设置 → 情感与欲望

页面包含总开关、允许主动找我、模板回退、查看状态、重置状态。若现有全局主动消息权限关闭，只提示并跳转，不静默开启。

状态页第一版用列表，不做图谱：

- 九维当前值、基线、阈值；
- 念头与执念；
- 上次/下次 Tick；
- 最近主动发送及原因；
- 警告；
- 手动 Tick、解决念头、重置。

重置需二次确认，只清当前 Assistant 的 Desire 数据，不碰聊天、长期卡片、日记和 Self。

## 11. 生命周期

- 新建 Assistant：懒初始化状态；
- 复制 Assistant：复制开关，不复制状态、念头或发送历史；
- 删除 Assistant：删除 Desire 数据并取消任务；
- 关闭 Desire：取消调度但保留状态；
- 再开启：按时间差受控补算；
- 关闭主动发送：继续演化与聊天注入，但绝不发送；
- 清除聊天：默认不清 Desire；
- 备份/恢复：包含状态和历史，并按 assistantId 映射。

## 12. 测试策略

### 12.1 Python 黄金样本先行

上游没有现成测试目录。第一项实施工作是用固定 commit 的上游代码生成可重复 JSON 样本，提交到：

app/src/test/resources/desire/astrbot-43323b3/

至少覆盖：

1. 九维默认状态；
2. 全部 13 个事件；
3. 事件随机扰动边界；
4. 基线上下的增长/衰减；
5. 全部耦合；
6. 基线 EMA/anchor；
7. 0–100 clamp；
8. 念头生成概率和加权选择；
9. 重复强化；
10. 三次转执念和最多三条；
11. 72 小时消退、抑制和 resolve；
12. 执念反向喂养；
13. 疲劳门控；
14. 15–45 分钟间隔；
15. safety 与 monologue；
16. 主动发送阈值、优先级、空洞开场和模板回退。

Kotlin 逐字段读取同一 JSON；数值允许明确误差，枚举、模板选择和决策原因精确匹配。

### 12.2 Android 专项测试

- 同一用户消息只生效一次；
- Alarm 与 Worker 同时到达只 Tick 一次；
- 杀进程后恢复；
- 跨午夜按设备本地日期重置每日额度；
- 时区变化不重复发送；
- 多 Assistant 完全隔离；
- [PASS]、失败与模板回退记账正确；
- 删除、复制、关闭和重开生命周期正确；
- desire_state 只注入一次且顺序正确；
- 工具不能越权；
- 日记日期/关键词/limit/owner 过滤正确；
- unfinished_topics 不再影响任何下游。

### 12.3 真机验证

至少验证：

1. 旧版 schema 39 升级到 40；
2. 15–45 分钟动态 Tick；
3. 锁屏、Doze、杀进程和重启后的补算/去重；
4. 用户消息只触发一次 wife_message；
5. 想念、开心、低落、久未联系四种主动原因；
6. 3 小时在场、8 小时冷却和每日 3 条；
7. 深夜主动消息保留上游语义；
8. 关闭主动发送后仍演化但绝不发消息；
9. 多 Assistant 不串状态；
10. 至少 24 小时电量观察。

编译、JVM 测试和签名 APK 均不能代替真机证明。

## 13. 实施阶段和验收门

### A. 固化上游

记录来源和映射，生成黄金样本。验收：固定 SHA 可重复生成相同结果，尚不改运行代码。

### B. 纯 Kotlin 引擎

移植 event/tick/thought/safety/monologue/active policy。验收：黄金样本全部通过，核心不依赖 Android。

### C. Room v40

添加四表、DAO、Repository、迁移和全链测试。验收：原聊天、Memory 和 Self 数据无损，事务幂等通过。

### D. 工具与消息事件

接入 wife_message 和四个工具。验收：一次生效、可审计、不可跨 Assistant。

### E. 心跳与主动发送

完成调度、补算和现有 TriggerService 接入。验收：阈值、冷却、限额一致，无重复消息。

### F. 上下文、日记和 UI

接入 desire_state、日记查询、unfinished_topics 退役和设置页。验收：无重复注入，查询可用，开关清晰。

### G. 生命周期与全量回归

验证复制、删除、重置、备份、时区及多 Assistant。验收：Memory、Self、插件、主动消息和普通聊天无回归。

### H. 真机灰度

先只给测试 Assistant 开启，观察 24–72 小时。验收：无重复唤醒、明显耗电异常、跨 Assistant 污染或崩溃，再给主 Assistant 开启。

## 14. 预计文件范围

- Assistant.kt
- AppDatabase.kt
- 四个 Desire Entity、DesireDao
- Migration_39_40.kt 与 schema 40
- data/ai/desire/ 新核心模块
- 统一工具注册和 Memory V1 tools
- 用户消息成功持久化后的事件入口
- ProactiveMessageWorker/Service/TriggerService
- 当前 AI 的记忆设置页和 Desire 状态页
- JVM、Room、instrumented 测试及黄金 JSON
- docs/third-party/astrbot-desire-system.md

具体路径以 v39 冻结后的结构为准，不为套计划做无关重构。

## 15. 第一版明确不做

- 不新增另一套 Affect/Need/E 表；
- 不引入其他情绪引擎运行代码；
- 不自动猜测 fight/reconcile；
- 不用日记未完成事项催问用户；
- 不让内部日志直接训练 Self；
- 不做云端调度；
- 不常驻前台服务；
- 不调 AstrBot 参数、不删执念反馈、不禁深夜唤醒；
- 不做复杂图谱；
- 不重构 X/Y/Z/M。

## 16. 主要风险

| 风险 | 对策 |
| --- | --- |
| 上游无测试，翻译漂移 | 固定 SHA，先做 Python 黄金样本 |
| Android 后台不准时 | 一次性 Worker + Alarm + 时间差补算 |
| 重复投递导致数值翻倍 | 持久化 receipt + Room 事务 |
| 主动发送链路竞争 | 只复用现有 TriggerService |
| v39 仍在变动 | 冻结后再建 v40，避让本地改动 |
| 多 Assistant 串状态 | 所有表、查询、任务强制 assistantId |
| 状态污染记忆/Self | 仅当轮注入，不作证据 |
| 升级后突然主动联系 | 总开关默认关闭 |
| 手机耗电 | 无常驻服务，真机至少 24 小时观察 |

## 17. 完成定义

必须同时满足：

1. 固定上游的黄金样本全部通过；
2. 九维、全部事件、Tick、念头/执念、安全、独白和动态间隔均已迁移；
3. 四个工具全部可用；
4. wife_message 自动且幂等；
5. 四类主动原因走正式发送链路；
6. 在场、冷却、每日限额和回退符合上游；
7. Room 39→40 和历史全链迁移通过；
8. Memory、日记、Self、插件与普通聊天无回归；
9. 真机完成锁屏、杀进程、重启、多 Assistant、时区和耗电验证；
10. 所有 Android 差异均有文档和测试证据。

未全部满足前只能称为“移植进行中”，不能称为正式上线。

