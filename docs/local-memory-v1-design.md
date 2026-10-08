# 橘瓣本地长期记忆 V1 设计与实施方案

> 文档状态：第一版实施基线（仅设计，尚未编码）  
> 日期：2026-09-08  
> 目标读者：接手实现的新 Codex 窗口或开发者  
> 工作区：`D:\orangechat`

## 0. 接手窗口必须先做的事

1. 完整阅读仓库根目录的 `AGENTS.md` 与 `MEMORY.md`。
2. 执行 `git status --short`，保留当前工作树里与本功能无关的修改；不得覆盖或清理已有改动。
3. 本任务涉及 Kotlin、Room 与提示词代码，开始改代码前必须阅读并使用 `Karpathy Guidelines` skill。
4. 先按本文完成静态核对，再逐阶段实现；不要一次性重写旧记忆、外置记忆和聊天管线。
5. 本文中的“小雨”是所有面向模型、界面和自然语言日志的称呼。内部协议、枚举和数据库字段可继续使用 `user`、`owner`、`MessageRole.USER`，不要为了文案要求破坏已有数据协议。

## 1. 最终决策

V1 采用：**本地保存、本地检索、当前聊天主模型按需整理**。

- 聊天记录继续由橘瓣现有 `Conversation` / `MessageNode` 保存，作为原始证据，不再复制一份“原始事件表”。
- 新建本地长期记忆卡、每日记忆日记、来源映射、修订记录、向量索引和整理任务状态。
- 关键词与向量检索都在手机端完成；没有配置向量模型时，关键词检索必须独立可用。
- 记忆整理不按 3 轮、15 轮等固定轮数触发。默认按“记忆日”夜间整理，并在 App 下次启动时补偿漏跑任务。
- 整理由该助手当前使用的聊天主模型完成，不单独强制配置“廉价提取模型”。模型负责提出修改建议，本地规则负责校验和落库。
- 明确要求“记住”时，主模型可在当轮通过记忆工具直接写入，不必等到夜间。
- 自动召回没有下限：可以返回 0 条。默认上限 8 条，可配置；低信息消息可直接跳过自动召回。
- 召回结果放在消息序列靠后位置：插件动态上下文之后、当前真实 USER 消息之前，以保留尽可能长的稳定缓存前缀。
- Supabase 外置记忆保持现状且默认不配置，不作为 V1 依赖，也不自动迁移或删除其数据。
- 知识图谱不进入 V1 主链路。V1 预留来源、事实键、有效期和状态字段；以后图谱只能作为由记忆卡派生、可重建的索引，不能成为第二套事实主库。

## 2. 为什么选择这套方案

### 2.1 与完全本地离线的区别

“本地保存和检索”只说明记忆数据库不在 Supabase。若聊天主模型或 embedding 模型是在线服务，整理或向量化时仍会把必要文本发给对应模型提供商。真正完全离线还要求本地聊天模型和本地 embedding 模型，不属于 V1 强制目标。

### 2.2 与完全云端 Supabase 的区别

Supabase 更容易做稳定定时任务、跨设备和大规模查询，但会引入服务费用、网络依赖、RLS 多租户隔离和密钥管理。橘瓣当前是个人陪伴型 App，记忆规模在可预见阶段适合 SQLite/Room；本地方案的检索质量不会天然低于云端，质量主要由记忆内容、分词、embedding、融合排序和当前事实过滤决定。

### 2.3 与完整移植 LMC-5 的区别

LMC-5 面向长时间运行的 agent，完整设计包含 raw events、chunks、curated memories、XYZEM、关系扩展、夜间 hippocampus 和巡检。橘瓣已经保存完整聊天，陪伴聊天碎片密度又高，照搬 raw event → 固定窗口 chunk → observation → hippocampus 会产生重复存储和较重的整理链。

V1 只借鉴 LMC-5 的可靠部分：

- 原始证据与精选记忆分离；
- 记忆必须有来源；
- `fact_key` 与当前/历史/作废状态；
- 模型只提议，本地校验后写入；
- 关键词是不可失效的底线，向量只是增强；
- embedding 独立存储模型、维度和内容哈希；
- 冲突先进入待确认，不让ionship 模型无条件覆盖事实；
- 关系或知识图谱是派生层，不等于记忆正文。

V1 不照搬：

- 为聊天记录再建一套 raw-event 日志；
- 固定 20 条或固定轮数 chunk；
- 完整 XYZEM 五轴；
- 默认两跳图谱扩展；
- PostgreSQL/pgvector/VPS 常驻任务；
- 把工具日志、系统消息、动态环境都作为长期人格记忆。

## 3. 产品目标与非目标

### 3.1 V1 必须实现

1. 新旧记忆模式互不干扰，可单独关闭。
2. 主模型可搜索、新增、编辑、归档和恢复长期记忆。
3. 完成一次正常 USER → ASSISTANT 回合后，原聊天照常保存，不同步提取碎片。
4. 每个记忆日由主模型生成一篇每日记忆日记，并对长期记忆卡提出补丁。
5. 新窗口仍能召回旧窗口形成的记忆；发送给上游的聊天上下文长度不会影响夜间整理进度。
6. 关键词检索覆盖整个“符合权限且仍有效”的记忆库；向量检索可选。
7. 自动注入 0～8 条（默认）真正相关的记忆，不为了凑数注入无关内容。
8. 记忆来源可追溯到 conversation、node、message；记忆被修改时可查看修订历史并撤销。
9. AI 的“删除”是软归档；永久删除只在可见 UI 中由小雨主动执行。
10. 无网、embedding 失败、夜间任务漏跑或模型输出非法时，聊天本身仍应正常工作。

### 3.2 V1 明确不做

- Supabase 实时同步与多设备冲突合并。
- 完整知识图谱、自动多跳推理、实体画像和“三元一场认知”。
- 由轮数触发的碎片抽取。
- 自动永久删除记忆。
- 将全部聊天内容无条件发给另一个整理模型。
- 为“嗯”“哦”“好”等低信息回复强制生成 embedding 或召回至少若干条记忆。
- 承诺夜间任务在 04:00 精确执行；Android 后台调度只能做 best effort。

## 4. 总体架构

```text
现有 Conversation / MessageNode（原始聊天证据）
                    │
          ┌─────────┴─────────┐
          │                   │
   当轮显式记忆工具       每日整理调度器
          │                   │
          │          主聊天模型输出 MemoryPatch
          │                   │
          └─────────┬─────────┘
                    ▼
        本地 MemoryRepositoryV1
        ├─ 长期记忆卡 memory_cards
        ├─ 每日记忆日记 memory_daily_notes
        ├─ 来源 memory_sources
        ├─ 修订 memory_revisions
        ├─ 任务 memory_consolidation_runs
        ├─ FTS memory_card_fts
        └─ 向量 memory_embeddings（可选）
                    │
       关键词召回 ──┼── 向量召回
                    ▼
             融合、去重、过滤
                    ▼
插件动态上下文 → 记忆上下文 → 当前真实 USER
```

核心约束：`Conversation/MessageNode` 是原始证据，`memory_cards` 是会影响未来对话的主要长期记忆，FTS、embedding 和未来图谱都是可重建索引。

## 5. 与橘瓣现有代码的对应关系

### 5.1 必须复用

| 现有位置 | 可复用内容 | 实施注意 |
|---|---|---|
| `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt` | `Conversation`、`MessageNode`、选中分支、message/node UUID | 夜间整理只读取 `currentMessages` 所代表的当前选中分支；来源同时保存 nodeId 和 messageId |
| `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt` | 对话最终持久化流程 | 成功路径在 `saveConversation(...)` 完成后才可标记“该日期有待整理内容”；不要挂到失败/取消也会触发的 completion 清理逻辑 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt` | provider 调用、工具循环、上下文组装、`limitContext` | 召回快照在进入工具循环前只计算一次，循环内复用；不能每个 tool step 重新向量化和检索 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/SystemPromptBuilder.kt` | 稳定系统前缀 | 只放固定的记忆使用规则，不放每轮变化的实际记忆正文 |
| `app/src/main/java/me/rerere/rikkahub/data/db/fts/MessageFtsManager.kt` | SQLite FTS 与 `jieba_query(?)` 的中文检索做法 | 为记忆卡建立独立 FTS 表/manager；不要直接搜索聊天 FTS 后把原始聊天全部注入 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt` | 现有 Tool schema 与执行回调模式 | 不直接扩大旧 `AssistantMemory` 工具；为 LOCAL_V1 新建工具协议，避免破坏旧模式 |
| `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ToolSurfaceBuilder.kt` | 按助手配置构建工具面 | 根据 memory mode 只暴露一套记忆工具，禁止旧工具和 V1 工具同时出现 |
| `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`、`di/DataSourceModule.kt` | Room 注册、WAL、显式 migration | 当前数据库版本为 30；若采用主数据库方案，需要显式 30→31 migration 与 migration test |
| `app/src/main/java/me/rerere/rikkahub/workflow/trigger/TimeCronTrigger.kt` | AlarmManager + WorkManager 补偿、唯一任务思路 | 只借鉴调度和幂等 claim，不要让夜间记忆依赖精确闹钟权限 |

### 5.2 仅兼容，不继续扩建

| 现有位置 | 当前局限 | V1 处理 |
|---|---|---|
| `MemoryBankEntity.kt` / `MemoryBankDAO.kt` | 一张表同时装消息、摘要、手工记忆；embedding 为 JSON 字符串；缺少状态、来源、修订、事实键 | 保留旧表，不把它直接迁成 V1 主表 |
| `MemoryBankService.kt` | 关键词主要是 LIKE；向量召回一次加载所有向量并排序；向量任务已是 no-op | LOCAL_V1 使用新 repository；旧服务只服务旧页面/旧数据 |
| `ExternalMemory.kt` / `ExternalMemoryService.kt` | Supabase 直接 REST；默认表 `chat_messages` / `memory_summaries`；部分向量在下载全表后于本地排序 | 保持外置模式可用，但不作为 V1 后端，也不静默上传 V1 数据 |
| `DiarySummaryService.kt` | 本地调度/生成已标记废弃，注释假设 Supabase Edge Function 处理 | 不复活该类；新建职责清晰的 `LocalMemoryConsolidationWorker/Service` |
| `Assistant.enableMemory/useGlobalMemory/externalMemoryIds` | 三个旧布尔/集合难以表达互斥模式 | 增加枚举模式并做兼容映射，旧字段继续反序列化 |

### 5.3 推荐的新增代码边界

命名可以微调，但职责不要混在一个“大而全 Service”里：

```text
data/memory/v1/
├─ model/MemoryCard.kt
├─ model/MemoryPatch.kt
├─ db/MemoryCardEntity.kt
├─ db/MemorySourceEntity.kt
├─ db/MemoryRevisionEntity.kt
├─ db/MemoryDailyNoteEntity.kt
├─ db/MemoryEmbeddingEntity.kt
├─ db/MemoryConsolidationRunEntity.kt
├─ db/MemoryV1Dao.kt
├─ MemoryRepositoryV1.kt
├─ MemoryRecallService.kt
├─ MemoryPatchValidator.kt
├─ MemoryConsolidationService.kt
├─ MemoryConsolidationScheduler.kt
└─ LocalMemoryConsolidationWorker.kt

data/ai/tools/
└─ MemoryV1Tools.kt

ui/pages/memory/
└─ 在现有记忆页上新增 V1 分区，或新建 LocalMemoryPage
```

## 6. 配置与兼容模式

建议新增：

```text
MemoryMode.OFF
MemoryMode.LEGACY_SIMPLE
MemoryMode.LOCAL_V1
MemoryMode.EXTERNAL
```

若产品仍希望本地与外置可并存，不要在 V1 直接允许双写；先把 `EXTERNAL` 视为独立模式。未来需要同步时再增加明确的 `LOCAL_V1_WITH_ENCRYPTED_BACKUP`，而不是含糊的多个开关。

### 6.1 旧半成品的处理决定：隔离，不立即物理删除

当前实际使用中旧本地记忆已关闭、Supabase 外置记忆也未配置，因此 V1 上线时应让它们完全退出默认运行路径。但首版不要删除旧 Room 表、DAO、序列化字段和 migration 历史，原因是老安装包可能已有相应字段或数据，直接删除会增加数据库升级、设置反序列化和回滚风险。

隔离必须达到以下可验证状态：

1. 设置页默认只展示“关闭”和“新版本地记忆”；旧版简单记忆与外置 Supabase 移入“旧版/实验功能”折叠区，或在首版直接隐藏入口。
2. `MemoryMode.OFF` 和 `LOCAL_V1` 下，`GenerationHandler`、`ToolSurfaceBuilder`、`ChatService`、主动消息和工作流路径都不得调用 `MemoryRepository`、`MemoryBankService` 或 `ExternalMemoryService`。
3. `LOCAL_V1` 下只暴露 `MemoryV1Tools`，不能同时暴露旧 `memory_tool`。
4. 旧 `enableMemory`、`useGlobalMemory`、`externalMemoryIds` 继续参与反序列化兼容，但不能暗中打开 LOCAL_V1。
5. `MemoryBankEntity`、旧 `MemoryEntity` 及其表暂时保留为 dormant schema；不做新写入、不参与召回、不显示在新版记忆页。
6. `ExternalMemoryService` 与 `DiarySummaryService` 暂时保留为独立 adapter/legacy 包；没有显式选择 EXTERNAL 模式时不初始化网络请求、不注册其整理任务。
7. 增加回归测试，断言 LOCAL_V1 正常聊天中旧本地 DAO 与 Supabase HTTP client 的调用次数均为 0。

等 LOCAL_V1 经至少一个稳定版本验证后，再单独立项做“物理删除”：先提供旧数据导出/迁移预览，再移除 UI 和运行代码；数据库表应通过显式 migration 清理，不能使用 destructive migration。若未来仍希望支持 Supabase，则只删除旧实现，保留重写后的可选同步 adapter。

建议配置项：

- `memoryMode`：默认 `OFF`，由小雨主动开启。
- `memoryScope`：`ASSISTANT` 或 `GLOBAL`；沿用旧全局记忆语义，但 V1 使用稳定 owner key。
- `automaticRecallEnabled`：默认开。
- `maxAutoRecallCount`：默认 8，允许 1～20；这是上限，不是必须返回数。
- `memoryInjectionTokenBudget`：默认约 1800 tokens，条数和 token 预算同时约束。
- `dailyConsolidationEnabled`：默认开（仅在 LOCAL_V1 下生效）。
- `memoryDayBoundaryHour`：默认本地时间 04:00。
- `embeddingModelId`：可空；为空时关键词检索仍正常工作。
- `consolidationOnUnmeteredOnly`、`consolidationWhileChargingOnly`：可选约束，默认不强制，避免任务长期不跑。

兼容原则：已有 `enableMemory=true` 的助手不能自动变成 LOCAL_V1。首次升级保持原行为；设置页由小雨明确选择“新版本地记忆”。

## 7. 数据模型

### 7.1 `memory_cards`：长期记忆唯一主库

建议字段：

| 字段 | 含义 |
|---|---|
| `id: String(UUID)` | 稳定 ID，不使用可重排的自增 Int 暴露给模型 |
| `owner_type` / `owner_id` | assistant/global 隔离 |
| `content` | 一条完整、可独立理解的记忆，建议不超过 300 个中文字符 |
| `category` | preference / profile / relationship / plan / experience / boundary / other |
| `fact_key` | 可空；同一可演化事实的稳定键，如 `person.residence.current` |
| `status` | ACTIVE / REVIEW / SUPERSEDED / ARCHIVED |
| `confidence` | 0～1，模型建议值经本地裁剪；不单独决定是否注入 |
| `importance` | 0～1，用于同等相关度下排序 |
| `valid_from` / `valid_to` | 可空；区分历史事实与当前事实 |
| `superseded_by` | 被哪条新记忆替代 |
| `created_at` / `updated_at` | 时间戳 |
| `revision` | 乐观锁版本号，每次修改 +1 |
| `created_by` / `updated_by` | TOOL / NIGHTLY_MODEL / MANUAL |

强约束：默认自动召回只读取 `ACTIVE`；`REVIEW` 只在管理页或显式搜索包含待确认项时显示；`SUPERSEDED` 和 `ARCHIVED` 不自动注入。

### 7.2 `memory_sources`：来源映射

字段至少包含：`id`、`memory_id`、`conversation_id`、`node_id`、`message_id`、`role`、`source_kind`、`source_hash`、`created_at`。

- 不复制整段原始聊天，避免重复存储；原文从现有 Conversation 读取。
- `source_hash` 用于识别同一证据是否已处理。
- 如果原对话被删除，来源变成“证据不可用”，记忆卡不应悄悄跟着消失。管理页应提示来源已缺失，并允许小雨决定保留还是删除。
- 如果未来产品决定“删除对话必须连同派生记忆删除”，必须新增明确的删除选项，不能默默改变隐私语义。

### 7.3 `memory_revisions`：可撤销历史

字段：`id`、`memory_id`、`revision`、`operation`、`before_content`、`after_content`、`before_status`、`after_status`、`reason`、`actor`、`created_at`、`run_id`。

每次 ADD/UPDATE/MERGE/SUPERSEDE/ARCHIVE/RESTORE 都写修订。模型给出的 `reason` 仅用于解释，不作为真相。撤销操作本身也生成新 revision，不回写历史行。

### 7.4 `memory_daily_notes`：每日记忆日记

唯一键：`owner_id + memory_date`。字段建议：`summary`、`emotional_thread`、`unfinished_topics`、`source_fingerprint`、`status`、`created_at`、`updated_at`、`revision`。

每日记忆日记用于保留一天的整体氛围、连续事件和未完话题；它不是强行拆成许多原子碎片，也不直接等同于长期事实。自动召回时可作为独立候选，但排序权重低于命中的长期记忆卡。

### 7.5 `memory_embeddings`：派生向量

字段：`owner_type`、`owner_id`、`embedding_provider`、`embedding_model`、`dimension`、`input_type`、`content_hash`、`vector_blob`、`status`、`updated_at`。

- Float32 使用 BLOB，不再使用 JSON 浮点数组。
- 同一个可查询索引不得混合不同模型或维度。
- 内容哈希不变时不得重复向量化。
- 换模型后标记旧向量不可用，后台分批重建；关键词检索继续服务。
- V1 数据量小时可分页扫描 BLOB 并维护 Top-K 小根堆，禁止把所有对象和解析后的向量一次性留在内存。
- 数据量和设备实测证明线性扫描成为瓶颈后，再评估 `sqlite-vec`/HNSW；不要预先引入复杂 native 依赖。

### 7.6 `memory_consolidation_runs`：夜间整理状态

唯一键：`owner_id + memory_date`。字段：`status(PENDING/RUNNING/SUCCEEDED/FAILED/NEEDS_REVIEW)`、`source_fingerprint`、`attempt_count`、`model_id`、`started_at`、`finished_at`、`error_code`、`lease_until`。

它替代“距离上次累计 15 轮”的状态：新窗口、上下文截断、App 重启都不会影响日期任务。相同 `source_fingerprint` 已成功时直接跳过；聊天记录后来发生分支切换或编辑，fingerprint 改变后允许重跑并生成新 revision。

## 8. 写入与整理流程

### 8.1 聊天正常保存

继续使用现有 Conversation 持久化。一次生成只有满足以下条件才标记当天需要整理：

- 最终保存成功；
- 存在完整的 USER → ASSISTANT 回合；
- Assistant 最终消息不是失败占位；
- 没有未执行完的工具调用；
- LOCAL_V1 且每日整理开启。

这里只更新很轻的“日期待处理”状态，不调用模型、不提取碎片，也没有每日模型调用上限。

### 8.2 当轮显式写入

当小雨明确表达“记住这个”“以后不要这样”“我更喜欢……”等，聊天主模型可以调用管理工具：

1. 先 `memory_search` 查找可能已有的卡片。
2. 无同义卡才 create；已有卡用 update；事实被明确纠正时 supersede。
3. 本地校验 owner、revision、内容长度和来源 messageId。
4. 写主表、source、revision，并把 embedding 标为待更新。

工具写入与夜间整理可能看到同一事实。夜间模型必须接收当天已由工具写入的卡片和来源哈希；相同证据优先返回 NOOP 或 UPDATE，不能重复 ADD。

### 8.3 夜间整理

“记忆日”定义为本地 04:00 到次日 03:59:59。调度采用唯一的一次性 WorkManager 任务，允许系统延迟；App 冷启动/回到前台时扫描最近未完成日期并补跑。

推荐流程：

1. 对 `owner_id + memory_date` 获取本地 lease，防止闹钟、WorkManager、手动整理重复执行。
2. 从 Conversation 中读取该记忆日内所有相关聊天的当前选中分支。
3. 排除 SYSTEM、内部动态环境、记忆注入消息、纯工具协议噪声和失败占位；保留能理解对话所需的实际工具结果摘要。
4. 计算消息 ID、选中分支和内容哈希组成的 `source_fingerprint`。
5. 检索与当天聊天相关的现有 ACTIVE/REVIEW 记忆卡，供模型判断新增、更新和冲突。
6. 使用该助手当前聊天主模型发起内部整理请求；这不是可见聊天，不写入 Conversation。
7. 主模型一次返回：每日记忆日记 + `MemoryPatch[]`。
8. 本地严格解析和验证；整批使用事务落库。任何非法 ID、越权 owner、未知 action、revision 冲突都拒绝该操作。
9. 为新增或改变的卡片异步生成 embedding；embedding 失败不回滚文字记忆。
10. 标记 run 成功，并安排下一次任务。

如果一天聊天超过模型可接受长度：按会话和时间顺序切成若干临时批次，让同一主模型生成“临时当天片段摘要”，再将这些临时摘要、关键原话引用和相关旧卡输入最终整理。临时摘要只存在任务工作区，不进入长期记忆和召回库，任务完成后删除。

### 8.4 整理模型输出协议

建议结构化 JSON：

```json
{
  "daily_note": {
    "summary": "...",
    "emotional_thread": "...",
    "unfinished_topics": ["..."]
  },
  "patches": [
    {
      "action": "ADD|UPDATE|MERGE|SUPERSEDE|ARCHIVE|NOOP",
      "target_id": null,
      "source_ids": ["message-uuid"],
      "expected_revision": null,
      "fact_key": null,
      "category": "preference",
      "content": "...",
      "confidence": 0.9,
      "importance": 0.7,
      "reason": "..."
    }
  ]
}
```

大白话解释：这不是让模型把数据库重写一遍，而是让它提交一张“修改清单”。`ADD` 是新增；`UPDATE` 是补充同一件事；`MERGE` 是合并重复卡；`SUPERSEDE` 是小雨明确纠正了旧事实；`ARCHIVE` 是已不适合继续影响聊天；`NOOP` 是今天没有值得长期保存的新内容。

提示词硬约束：

- 自然语言中始终称“小雨”，不称“用户”。
- 只记录未来确实有帮助、跨窗口仍有价值的内容。
- 可以保留情感温度，但不得把模型猜测写成事实。
- 一次闲聊可以只有每日记忆日记而没有长期记忆卡。
- 不因反复提及就重复建卡；优先更新已有卡。
- 纠正事实必须有明确来源，不得仅凭新内容“看起来更合理”。
- 敏感信息策略沿用或收紧旧工具规则，并在设置页明确告知哪些内容不会自动保存。

## 9. 记忆工具设计

建议拆成两个工具，降低模型误操作概率：

### 9.1 `memory_search`

参数：`query`、`limit`（默认 8，最大 20）、`scope`、`include_archived`（默认 false）。返回 ID、状态、revision、内容摘要、更新时间和来源可用性。

这是显式工具搜索，不受自动注入上限 8 的约束，但单次仍最多 20，并支持继续缩小查询；它搜索整个符合 owner/状态权限的库，不只是最近几十条。

### 9.2 `memory_manage`

action：`create`、`update`、`supersede`、`archive`、`restore`。修改已有记录必须提供 `id + expected_revision`；revision 已变化则返回冲突并要求模型重新搜索。

- `archive` 是 AI 所谓的“删除”，可恢复。
- `restore` 只能恢复归档项。
- 永久删除不开放给 AI 工具。
- `merge` 在 V1 夜间内部 patch 中支持，暂不开放给聊天工具，避免模型误合并。

工具返回完整、可机器读取的结果，不在聊天界面伪装成功。任何失败要明确返回错误码，不能让模型以为已写入。

## 10. 自动召回

### 10.1 是否需要召回

在生成 query embedding 前先做本地轻量判断：

- 纯表情、纯标点、单独“嗯/哦/好/哈哈”等通常跳过；
- 但出现已知实体名、明确指代、时间追问、偏好/计划/关系词时不能仅按字数跳过；
- 规则不确定时允许进行关键词检索，但仍可最终返回 0 条。

这只是节省无意义检索，不是语义意图分类器。不要为此额外调用一个模型。

### 10.2 全库搜索与候选数量

“全库搜索”指 SQL/FTS/向量计算的检索范围覆盖全部符合条件的 ACTIVE 记忆，而不是把全库加载进提示词。

默认自动注入上限为 8 时，可让每条通道先各取 `maxInject × 5 = 40` 个候选：

- 关键词通道搜索全库后返回 Top 40；
- 向量通道搜索全库后返回 Top 40；
- 两路结果合并、去重、重排后只注入 0～8 条。

“40”是每个检索通道的候选返回上限，不是只搜索前 40 条记录。

### 10.3 关键词通道

独立的 memory FTS 使用现有 `jieba_query(?)` 能力。查询字段至少包含 `content`、可选 title/category/实体别名。排序使用 BM25/rank 并结合更新时间，但不能让“最新”压过明显更相关的旧记忆。

### 10.4 向量通道

- 只有配置可用 embedding 模型时启用。
- 当前 query 使用 query input type，记忆卡使用 document input type（provider 支持时）。
- 远程 embedding 会传输 query 和待向量化的记忆内容，设置页必须明确说明；可提供“只用关键词检索”。
- 查询失败立即降级关键词，不阻断聊天。
- 本地线性扫描按页读取向量、逐个计算余弦、只保留 Top-K；禁止 `getAllVectorizedMemories()` 后将全部 JSON 转成 `List<Float>`。

### 10.5 融合与过滤

V1 推荐 Reciprocal Rank Fusion（RRF），避免直接混合不可比的 BM25 和 cosine 原始分数。随后应用：

- 状态过滤；
- owner/scope 权限；
- 同一 `fact_key` 只保留当前 ACTIVE 版本；
- content hash/相似内容去重；
- 来源与当前消息完全相同的当轮记录去重；
- token budget；
- 最低相关度门槛。

不得把“重要度高”当作无条件注入理由。相关度是第一门槛，importance 只用于相关候选之间排序。

## 11. 注入位置与缓存

现有 `GenerationHandler` 的 provider 消息大致为：稳定 system → 截断后的历史 → 外置记忆 → 最近聊天 → dynamic environment → plugin context → 当前真实 USER。

LOCAL_V1 应新增独立、临时的 USER 消息，最终顺序为：

```text
稳定 SystemPromptBuilder
历史消息
旧模式上下文（仅相应模式启用）
最近聊天（若启用）
dynamic_environment
plugin context
local_memory_context   ← 新增
当前真实 USER          ← 最后
```

理由：每轮变化的记忆越靠后，前面的 system/history/plugin 前缀越容易命中 provider 的 prefix cache；记忆又必须位于当前问题之前，才能被模型用于回答。

实现要求：

1. `local_memory_context` 带 `metadata.memory_context=true`，只发给 provider，不写回 Conversation。
2. Chat Completions 的 same-role normalization 必须像保护 `dynamic_environment` 一样保护 `memory_context`，不能把它与 plugin USER 或真实 USER 合并。
3. 其他 provider 若有角色交替限制，可在最终 provider adapter 层按其协议合并文本，但必须保留逻辑边界和顺序。
4. 召回结果在 `generateText` 的工具循环外计算一次；后续 tool steps 使用同一快照。
5. 系统提示词只描述如何使用 `<memory_context>`，不包含动态记忆正文。
6. 最终 payload 测试必须断言顺序和 metadata；只编译通过不能证明缓存边界正确。

建议注入文本：

```text
<memory_context generated_at="...">
以下是与小雨当前话题可能相关的长期记忆，仅在确实相关时自然参考；
若记忆与小雨本轮明确表达冲突，以本轮为准，并可通过记忆工具提出修正。
1. [memory_id=...] ...
</memory_context>
```

## 12. UI 与交互

设置页需要明确区分：关闭、旧版简单记忆、新版本地记忆、外置 Supabase。LOCAL_V1 页面至少包含：

- 自动召回开关与最大条数（默认 8）；
- 每日整理开关、记忆日边界、上次成功时间、待处理日期；
- embedding 模型与“只使用关键词”选项；
- “立即整理今天/昨日”按钮；
- 本地存储及远程模型隐私说明；
- 导出与永久删除入口。

记忆管理页至少包含：

- 全库搜索；
- ACTIVE / REVIEW / SUPERSEDED / ARCHIVED 筛选；
- 长期记忆卡与每日记忆日记分区；
- 查看来源，跳转到仍存在的原聊天；
- 编辑、归档、恢复、查看修订、撤销；
- 永久删除的二次确认和明确影响范围。

面向小雨的文案不使用“用户”。内部 debug 日志不要打印记忆正文、模型整理 prompt、embedding 输入或 Supabase/Provider 密钥。

## 13. 安全与隐私边界

V1 的“本地”承诺应准确表述为：记忆主库保存在 Android 应用私有目录，不自动上传 Supabase。

- 第一阶段不建议为了本功能直接加密整个现有 `rikka_hub` Room；这会把迁移风险扩散到全部聊天数据。
- 若要做静态加密，优先评估独立的 `orange_memory.db` + SQLCipher + Android Keystore，再单独完成升级、备份和性能验证。但现有聊天数据库仍保存原始证据，只加密记忆库并不等于聊天也加密。
- 明确 Android Auto Backup 规则；不得让“本地不上传 Supabase”被系统未说明的云备份语义混淆。
- 导出文件默认加密或明确警告其包含私人记忆。
- 远程主模型整理前只发送该日期必要聊天、相关旧卡和结构化规则，不发送全库。
- 远程 embedding 前提供关闭选项；关闭后保持 FTS 可用。

## 14. 知识图谱的后续预留

V1 不创建图谱表也可以平滑升级，因为 `fact_key`、来源、状态、有效期已经保留了关键基础。

V2 可先增加轻量实体索引：

```text
entities(id, canonical_name, type, status)
entity_aliases(entity_id, alias, normalized_alias)
memory_entities(memory_id, entity_id, source_id, confidence)
```

V3 再增加：

```text
memory_relations(
  id, source_entity_id, target_entity_id, relation_type,
  source_memory_id, confidence, valid_from, valid_to, status
)
```

强约束：

- 图谱关系必须引用来源记忆；没有来源不入图。
- 记忆归档、合并、修订或 supersede 时，对应边失效或重建。
- 图谱只帮助“找到关联”，最终注入的是有上下文的记忆卡，不是一堆裸三元组。
- 默认最多一跳；两跳必须按关系类型白名单、置信度和数量预算控制。
- 图谱可从主库重建，因此绝不能与 memory_cards 分别成为两套真相。
- 在本地规模和查询实测证明 SQLite 不够前，不迁移到 Supabase/PostgreSQL。

## 15. 数据迁移策略

V1 上线时不自动吞并旧数据：

1. `LEGACY_SIMPLE` 继续读取现有 `AssistantMemory`。
2. 旧 `memory_bank` 继续由旧页面/服务使用。
3. 外置 Supabase 保持原配置和数据，不自动下载到本地。
4. LOCAL_V1 从空库开始，避免错误合并和不可逆污染。
5. 后续单独提供“预览迁移”：将旧记忆转换成 REVIEW 候选，展示去重结果，小雨确认后才写 ACTIVE。
6. 迁移全过程记录原始来源类型和旧 ID，可回滚；不删除旧记录。

Room 方案二选一，实施前先做小型 spike：

- **方案 A（V1 默认、改动少）**：在现有 AppDatabase 新增 V1 表，版本 30→31，沿用 WAL 与 FTS/Jieba。
- **方案 B（隐私增强、隔离好）**：独立 MemoryDatabase，便于未来 SQLCipher，但需要额外 DI、跨库一致性与备份规则。

推荐先采用 A 完成正确性；若“记忆静态加密”被确认为首发硬需求，则改选 B，不能在实现中途悄悄切换。

## 16. 分阶段实施顺序

### Phase 0：基线与契约测试

- 固化当前旧记忆、外置记忆和普通聊天行为。
- 建立旧半成品隔离测试：OFF/LOCAL_V1 不构建旧工具、不访问旧 DAO、不触发 Supabase 请求。
- 为 provider 最终消息顺序增加测试夹具。
- 记录当前 Room schema 30；新增 migration test 基础。
- 验收：未开启 LOCAL_V1 时请求 payload 与数据库行为不变。

### Phase 1：本地主库与管理 UI

- 新增 memory mode、表、DAO、repository、FTS 和 revision。
- 完成管理页的手工增改、归档、恢复、全库搜索。
- 暂不接模型、embedding、自动注入。
- 验收：进程重启后数据与修订仍在；不同 owner 不串库；FTS 搜索中文可用。

### Phase 2：AI 记忆工具

- 新增 `memory_search`、`memory_manage`。
- ToolSurfaceBuilder 按 mode 暴露唯一一套工具。
- 加入 expected_revision 和软删除。
- 验收：搜索后更新、冲突更新、归档恢复、越权 ID、非法输出均有测试。

### Phase 3：自动召回与晚注入

- 实现低信息 gate、FTS 候选、RRF、上限/token budget。
- 在插件动态上下文之后插入 metadata 标记的记忆消息。
- 召回一次、tool loop 复用。
- 验收：0 条路径；8 条上限；全库旧记录可命中；memory_context 不落库、不与真实 USER 合并；旧模式不重复注入。

### Phase 4：可选向量检索

- 新增 embedding 表、增量生成、失败降级、分页 Top-K。
- 关键词与向量融合，不混用不同模型/维度。
- 验收：无 embedding、超时、维度变化、内容更新、千级/万级模拟数据下内存峰值均受控。

### Phase 5：每日主模型整理

- 新增日期 dirty marker、consolidation run、unique worker、启动补偿和“立即整理”。
- 实现每日 note、MemoryPatch parser/validator/transaction。
- 超长日期加入临时分段摘要。
- 验收：新窗口、上下文截断、App 重启、跨 04:00、重复 worker、模型非法 JSON、部分 patch 冲突均不会重复写入或破坏聊天。

### Phase 6：隐私、导出与迁移预览

- 完成备份规则、日志脱敏、导出/导入、旧数据迁移预览。
- 根据产品决定是否单独立项做 SQLCipher。
- 验收：导出可恢复；取消迁移不写数据；删除范围明确；任何凭据不进入日志/导出。

### Phase 7（以后）：轻量实体与关系图谱

只有召回评测证明实体关联是主要漏召回来源后才开始。先实体别名，再一跳关系，最后才评估两跳；不实现无来源的“认知场”。

## 17. 测试与验收矩阵

### 17.1 单元测试

- MemoryPatch JSON 正常、缺字段、未知 action、越权 owner、错误 revision。
- ADD/UPDATE/MERGE/SUPERSEDE/NOOP 幂等。
- fact_key 当前版本唯一，历史版本可查看但不注入。
- FTS 中文、别名、空查询、特殊字符。
- RRF 去重、状态过滤、token budget、0 条结果和最大 8 条。
- 低信息 gate 不误伤明显实体或追问。
- embedding content_hash、维度隔离、失败降级、分页 Top-K。
- 记忆注入 metadata、顺序、same-role boundary。
- nightly fingerprint、lease、重复执行、跨时区/夏令时（如适用）和分支切换。

### 17.2 Room migration 测试

- 使用真实 schema 30 fixture 升级到 31。
- 旧 `memory`、`memory_bank`、conversation 和 FTS 数据保持不变。
- 新表、索引、唯一约束和外键符合预期。
- 升级失败不得 destructive migration。

### 17.3 集成测试

- LOCAL_V1 关闭：完全不读写新表、不增加 provider 消息。
- 仅关键词：断网仍能召回。
- 主模型整理失败：run 可重试，旧记忆不改变。
- 当轮工具写入 + 夜间同一事实：不重复。
- 删除/切换聊天分支后：source 状态与 fingerprint 正确。
- 多助手与 global scope：隔离和共享均符合设置。

### 17.4 性能与设备验证

构造 1k、5k、20k 记忆卡，测量：FTS 延迟、向量扫描延迟、峰值 Java heap、数据库大小、冷启动、整理耗电。性能结果必须来自真机或代表性模拟器；Gradle 编译成功不等于手机内存安全。

### 17.5 最终完成标准

- 新窗口能够召回旧窗口记忆。
- “嗯”可以 0 召回，相关问题能命中多年以前的全库记忆。
- 默认最多注入 8 条且在 token budget 内。
- 缓存前缀顺序有最终 provider payload 测试。
- 夜间漏跑可在下次启动补偿，无固定轮数依赖，无每日调用上限。
- AI 能搜索、创建、编辑、归档、恢复；永久删除只能由 UI 完成。
- 模型乱写 ID 或非法 patch 无法破坏数据库。
- Supabase 未配置时全功能正常，且没有任何隐式上传。
- 未开启新版模式的现有小雨行为无回归。

## 18. 需要参考的开源项目与文档

### 18.1 重点参考

1. **LMC-5**：<https://github.com/wuxuyun0606-collab/lmc-5>
   - 先读 `README.md`、`docs/CONNECTING_XYZEM.md`、`docs/IMPLEMENTATION_ORDER.md`、`docs/AUTOMATION_BOUNDARIES.md`、`docs/Y_RELATIONS.md`。
   - 重点看 `src/lmc5/` 的 SQLite/FTS/事实演化，以及 `extras/pgvector_backend/recall_pipeline.py` 和 `night_dream.py` 的生产思路。
   - 只借鉴契约与数据生命周期；不要直接把 Python/PostgreSQL 代码搬进 Android。
   - 复制任何代码前先核对仓库当前 LICENSE 与 OrangeChat AGPLv3 的兼容性，并保留要求的 attribution。

2. **小雨现有 AI Memory Gateway**：  
   `D:\Qi\MemoryConstellations-main\MemoryConstellations-main\ai-memory-gateway-main\ai-memory-gateway-main`
   - `memory_extractor.py`：结构化模型输出、实体规范化、证据约束；借鉴 prompt/validator 思想，不照搬多层提取。
   - `database.py`：`is_active`、来源关系、实体别名/合并、检索过滤；重点借鉴生命周期和同源去重。
   - `main.py`：软归档、恢复、事件整合、实体关系后台任务；用于理解踩坑，不移植 FastAPI 调度架构。
   - `tests/test_memory_extractor.py`、`test_memory_evolution.py`、`test_entity_dedup.py`、`test_entity_lifecycle.py`、`test_entity_relations.py`：将失败案例改写成 Kotlin 测试。

3. **Android Room 官方文档**：<https://developer.android.com/training/data-storage/room>
   - 参考实体、DAO、事务、migration test；禁止 destructive migration。

4. **Android WorkManager 官方文档**：<https://developer.android.com/develop/background-work/background-tasks/persistent>
   - 用于 best-effort 持久任务、网络约束、唯一工作和重试。不要宣称能在指定分钟绝对准时运行。

5. **SQLite FTS5 官方文档**：<https://sqlite.org/fts5.html>
   - 用于全库关键词检索、rank/BM25 和索引维护；中文 tokenizer 继续结合橘瓣现有 Jieba 扩展验证。

### 18.2 后续再评估

- `sqlite-vec`：<https://github.com/asg017/sqlite-vec>。只有线性向量扫描真机测试不达标时再评估 Android ABI、包体和 license。
- SQLCipher for Android：<https://github.com/sqlcipher/android-database-sqlcipher>。作为独立安全项目评估，不要顺手加密整个主数据库。
- Supabase pgvector：<https://supabase.com/docs/guides/ai/vector-columns>。仅供未来跨设备/云端大规模索引参考，不属于 V1 依赖。

## 19. 实施中的禁止事项

- 不用固定轮数替代每日整理。
- 不把原始聊天逐条复制进 memory_cards。
- 不把“候选 40”误解为只搜索数据库前 40 条。
- 不设置自动召回下限。
- 不在 tool loop 中重复检索。
- 不把动态记忆塞进稳定 SystemPromptBuilder。
- 不同时启用旧 memory_tool 与 LOCAL_V1 工具。
- 不在首版直接删除旧表、旧设置字段或历史 migration；先隔离，再在稳定版本中迁移清理。
- 不让模型绕过 validator 直接执行 SQL/DAO。
- 不让 AI 工具永久删除。
- 不因 embedding 不可用而中断聊天。
- 不在 V1 引入完整知识图谱或 Supabase 双写。
- 不把“本地存储”宣传成“全部推理数据绝不离开手机”。

## 20. 留给接手窗口的首个实施任务

只完成 Phase 0 和 Phase 1，不要同时接自动整理：

1. 检查工作树和现有测试。
2. 设计 `MemoryMode` 的向后兼容序列化。
3. 选择主库内新表（默认）或独立数据库（仅当静态加密是首发硬需求）。
4. 写 schema、30→31 migration、DAO、repository 和 migration tests。
5. 做管理页的手工 CRUD、软归档、恢复和 FTS 全库搜索。
6. 证明旧模式未改变后再进入 Phase 2。

若实现中发现本文与当前代码冲突，应以当前实际调用链和最终 provider payload 为证据，先记录差异并缩小修改，不要凭文档猜测重构。
