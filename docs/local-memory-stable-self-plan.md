# 橘瓣“稳定自我认识”最小实现计划

> 文档状态：待实施交接方案  
> 日期：2026-09-11  
> 工作区：`D:\orangechat`  
> 目标：让 AI 仅根据多次、可追溯的真实聊天经历，逐渐形成可修正的稳定自我认识；允许用户手动指定哪些稳定认识每轮常驻。

## 0. 接手窗口的第一步

1. 完整读取仓库根目录 `AGENTS.md` 与 `MEMORY.md`。
2. 执行 `git status --short`。当前本地记忆、主题、插件等存在大量未提交修改，禁止清理、覆盖或回退任何现有改动。
3. 本任务涉及 Kotlin、Room、Compose 和 Prompt，编码前必须读取并遵循 Karpathy Guidelines skill。
4. 先重新核对本文列出的类、数据库版本及方法签名；本文以 2026-09-11 的 Room v38 工作树为基线，但实施时以当前代码为准。
5. 只实现本文“范围内”内容。发现相邻问题时记录并报告，不顺手重构。

## 1. 一句话目标

实现以下单一闭环：

```text
真实聊天证据
  -> REVIEW self 候选卡
  -> 后续独立经历补充同一 fact_key 的证据
  -> 本地规则达到稳定门槛
  -> ACTIVE self 稳定认识
  -> 用户可手动设为常驻，或按普通相关性召回
  -> 新的真实证据可触发修正/替代
```

本功能不是“让模型自由改写人格”，而是给现有第一人称 `self` 记忆增加一个受证据约束的成熟过程。

## 2. 操作边界

### 2.1 本次必须完成

1. 夜间整理发现新的 `self` 倾向时，第一次只能创建 `REVIEW` 卡，不能直接进入 `ACTIVE`。
2. 同一稳定 `fact_key` 的后续真实经历必须合并到同一 REVIEW 卡，而不是重复建卡。
3. 只按独立真实经历计算成熟度，至少满足：
   - 3 个不同 `conversation_id`；
   - 覆盖至少 2 个不同记忆日；
   - 每份计数证据都能解析到真实 `memory_sources`；
   - 没有待处理的明确冲突。
4. 达到门槛后由本地规则将卡片 `REVIEW -> ACTIVE`，写入 revision；模型不能自行宣布“已经稳定”。
5. ACTIVE self 卡可由用户在记忆管理页手动切换“常驻”。
6. 常驻 self 卡每轮注入；非人工常驻的 ACTIVE self 卡继续走现有相关召回。
7. REVIEW self 卡不进入普通召回、时间线扩展、关系扩展或常驻注入。
8. 用户可以在管理页审核、编辑、启用、取消常驻、归档或拒绝候选。
9. 为 Room v38 增加显式升级迁移，并补 migration test。
10. 新增 JVM 测试覆盖证据独立性、成熟状态机、常驻权限与注入顺序。

### 2.2 本次明确不做

- 不新增 Family、SelfSchema、Affect、Need、InnerNarrative、Thought、Pulse、Impulse 或 ActionOutcome 表。
- 不实现情绪数值、主动消息触发、行动反馈学习或自主 Agent。
- 不新增聚类、LLM rerank、多跳图扩展或人格相似度算法。
- 不让 Daily Note、摘要、旧 self 卡、关系、时间线或模型推断成为新的真实证据。
- 不修改 Assistant 系统人设，不允许学习结果改变工具权限、安全规则或 Core Constitution。
- 不把全部 self 卡自动常驻，不设置自动常驻数量或 token 配额；常驻完全由用户逐卡指定。
- 不复用 `isProtected` 表示常驻。`isProtected` 只表示豁免冷记忆归档，两者语义必须独立。
- 不改变现有普通 preference/profile/relationship 等卡片的 NightDream 晋升规则。
- 不删除旧记忆、外置记忆或任何 Room 历史表。
- 不发布、不提交、不推送，除非用户另行明确要求。

## 3. 为什么 REVIEW 必须落在 memory_cards

当前 `NightDreamDecision.Review` 主要落入 `memory_proposals`。proposal 属于某个 `memory_date`，会随日期重跑或清理而过期，不适合作为跨天累积证据的长期身份候选。

本功能采用：

```text
self 候选 = memory_cards(category=self, status=REVIEW)
```

理由：

- 已有稳定 UUID；
- 已有 `fact_key`；
- 已有 `memory_sources`；
- 已有 `memory_revisions`；
- 管理页已有 REVIEW 卡片视图基础；
- 现有召回只读 ACTIVE，天然隔离未成熟候选。

普通 NightDream Review proposal 的行为保持不变。只为 `category=self` 增加专用候选路径，不把所有 proposal 改成 REVIEW 卡。

## 4. 最小数据模型变更

### 4.1 `memory_cards` 新增字段

```kotlin
@ColumnInfo("always_inject")
val alwaysInject: Boolean = false
```

语义：

- 只有 `category=self && status=ACTIVE` 时允许设置为 `true`；
- 其他类别或非 ACTIVE 状态必须为 `false`；
- 归档、SUPERSEDE 或降回 REVIEW 时必须自动清零；
- 常驻只影响读取/注入，不增加置信度、不制造证据、不阻止用户删除；
- `isProtected` 与 `alwaysInject` 可以独立存在。

### 4.2 不新增“证据计数”字段

独立证据数必须从 `memory_sources` 实时派生，避免缓存计数与来源表漂移。初期数据量小，直接查询足够。

需要增加 DAO 查询或 repository 聚合：

```kotlin
data class SelfEvidenceStats(
    val distinctConversationCount: Int,
    val distinctMemoryDateCount: Int,
)
```

`memory_sources` 没有 `memory_date`，记忆日通过 `message_created_at` 与现有 `MemoryDateUtils`、当前助手的 `memoryDayBoundaryHour` 计算。不得把 `created_at` 当作事件发生时间；历史来源缺少 `message_created_at` 时可以展示，但不得计入自动成熟。

### 4.3 Room 迁移

若实施时数据库仍为 v38：

```sql
ALTER TABLE memory_cards
ADD COLUMN always_inject INTEGER NOT NULL DEFAULT 0
```

要求：

- 数据库版本升级为 39；
- 新建 `Migration_38_39.kt`；
- 在 `AppDatabase` 注册迁移；
- 导出 `app/schemas/.../39.json`；
- 新建 `Migration_38_39_Test.kt`；
- 更新完整升级链测试，使 30 -> 39 仍可达；
- 禁止 destructive migration；
- 若当前工作树版本已高于 38，改用实际的下一个版本，不能制造重复版本。

## 5. self fact_key 契约

所有自动生成的 self 候选必须拥有非空、稳定的 `fact_key`：

```text
self.<domain>.<stable_trait>
```

示例：

```text
self.decision.verify_before_action
self.collaboration.finish_before_claiming_done
self.communication.state_uncertainty_explicitly
```

约束：

- 只允许小写 ASCII、数字、点和下划线；
- 必须以 `self.` 开头；
- key 描述稳定倾向，不编码日期、对话 ID、用户原话或情绪；
- 同一倾向后续必须复用原 key；
- 文案范围变窄或措辞优化时保留 key；
- 语义实质相反时不能复用 key 强行覆盖，应进入冲突审核或 SUPERSEDE。

建议新增一个小型纯函数验证器，不建立新的通用抽象：

```kotlin
internal fun isValidSelfFactKey(value: String?): Boolean
```

## 6. NightDream 输出契约调整

在 `MemoryConsolidationService.buildStructuredSystemPrompt()` 和用户提示中明确：

1. `self` 只描述 AI 在真实互动中反复表现出的长期倾向、自我认识或协作风格。
2. 一次情绪、一次行为、愿望、角色设定复述和模型自我想象都不是稳定自我。
3. 每个 self patch 必须提供：
   - `fact_key`；
   - 本次真实 `source_ids`；
   - 可在当日清洗消息中核验的 `evidence_snippet`；
   - 第一人称 `content`。
4. 提示中会提供已有 REVIEW/ACTIVE self 卡；相同语义必须引用其 `target_id` 和 `expected_revision`，禁止重复 ADD。
5. 新经历与已有认识冲突时，不得忽略反证或为维持一致性改写原话；应提出需要审核的 UPDATE/SUPERSEDE。
6. Daily Note、旧卡正文、模型摘要、timeline/relation 内容只能帮助定位，不能列入 `source_ids`。

### 6.1 给模型的 self 上下文

夜间结构化整理的上下文中增加一个有界列表：

```text
<existing_self_candidates>
- id / revision / fact_key / status / content
</existing_self_candidates>
```

只提供当前 owner 下的 REVIEW 与 ACTIVE self 卡。可以沿用整理上下文现有总预算；若需要裁剪，先保留 fact_key 与状态，再截断正文。不要把全量来源原文重复放入 prompt。

## 7. 写入状态机

### 7.1 第一次发现

模型返回合法、可接地的 `ADD self`：

```text
不存在相同 fact_key
  -> 创建 MemoryCardEntity
  -> category=self
  -> status=REVIEW
  -> alwaysInject=false
  -> 写入本次已解析 source
  -> 写 revision(operation=ADD, after_status=REVIEW)
  -> 不创建一个重复的普通 REVIEW proposal
```

即使模型给出 `confidence=1.0`，也不能直接 ACTIVE。

### 7.2 后续同类经历

模型返回指向 REVIEW self 卡的 UPDATE：

1. 校验 owner、target ID、revision、category、fact_key；
2. 校验至少一个 source ID 属于本次 cleaned dialogue；
3. 过滤已存在于该卡的 message ID；
4. 在一个 Room transaction 中：
   - 更新必要的卡片正文/置信度/revision；
   - 追加去重后的 `MemorySourceEntity`；
   - 写 `MemoryRevisionEntity`；
   - 重新计算独立证据统计；
   - 满足门槛则转 ACTIVE；
5. 如果本次没有新增有效来源，不增加成熟度，也不应仅靠改写正文更新置信度。

不得把相同 message 的多个 source 行计算成多次经历。

### 7.3 独立证据定义

自动成熟门槛固定为：

```text
distinct conversation_id >= 3
AND distinct memory_date >= 2
```

计数来源还必须满足：

- `conversation_id` 非空；
- `message_id` 非空；
- `message_created_at` 非空；
- 来源最终可以解析到现有真实 Conversation/Message，或来自本次已经完成解析的 `MemorySourceReference`；
- `source_kind` 不能是 SUMMARY、DAILY_NOTE、SCHEMA、NARRATIVE 等派生类型；当前已知 NIGHTLY_MODEL 表示写入者而不是证据类型，不能仅凭该值判定真实性。

如果验证真实消息的开销不适合放入 DAO，先在 Service/Repository 边界完成解析，再把“已验证来源”传入事务。DAO 不做业务推断。

### 7.4 置信度

不复制白皮书公式，也不引入复杂权重。采用确定性上限：

```text
1 个独立对话：confidence <= 0.60
2 个独立对话：confidence <= 0.75
达到成熟门槛：confidence <= 0.90
```

最终值可以是 `min(modelConfidence, localCap)`。模型置信度不能越过本地上限。

不要因为第 4、5 次证据自动推到 1.0；“稳定”不等于绝对正确。

### 7.5 冲突与反证

第一版不新增反证表和权重公式。

当本次内容与同 fact_key 的 ACTIVE/REVIEW self 卡实质冲突时：

- 禁止自动覆盖；
- 禁止自动提高置信度；
- 复用现有 Fact Audit 或 REVIEW proposal 进入人工审核；
- 保留原卡和新来源；
- 用户确认后才 UPDATE、SUPERSEDE 或 KEEP_BOTH；
- 范围收窄优先使用 UPDATE，例如从“信息不足时”改为“风险较高且信息不足时”。

### 7.6 人工审核捷径

用户在管理页明确批准 REVIEW self 卡时，可以直接转 ACTIVE，不要求自动成熟门槛，因为这是用户授权，而不是模型自我证明。

但必须：

- 保留现有来源；
- 写 revision，actor=`MANUAL`；
- 默认 `alwaysInject=false`；
- 是否常驻由用户单独选择，不能在批准时自动打开。

## 8. 手动常驻

### 8.1 UI

在 `LocalMemoryManagerPage` 的卡片详情/编辑区域中，仅对 `ACTIVE + self` 显示开关：

```text
常驻于 AI 自我认识
开启后，每轮对话都会把这条认识提供给当前 AI。
```

非 ACTIVE self 卡可显示禁用开关及说明“确认成为稳定认识后才能常驻”，也可以直接隐藏；选择与现有页面交互最一致的最小实现。

### 8.2 Repository 约束

新增专用方法：

```kotlin
suspend fun setSelfAlwaysInject(cardId: String, enabled: Boolean)
```

它必须在 repository 内重新读取卡片并校验：

```text
owner 可访问
category == self
status == ACTIVE
```

不能只依赖 UI 隐藏开关。非法开启应返回明确错误或抛出受控 `IllegalArgumentException`。

切换常驻属于用户可见状态变更，建议写 revision 或至少写审计记录。为保持现有 revision 语义完整，推荐写 revision，operation 可新增 `SET_ALWAYS_INJECT`，before/after content/status 保持不变，reason 记录 enabled 状态；不要为了记录这个布尔值改造整个 revision 表。

### 8.3 状态联动

以下操作必须强制 `alwaysInject=false`：

- ARCHIVE；
- SUPERSEDE；
- 变更 category 离开 self；
- 任何 ACTIVE -> REVIEW 的降级；
- 永久删除自然删除该字段所在行。

## 9. 常驻注入与普通召回

### 9.1 查询

DAO 增加精确查询：

```sql
SELECT * FROM memory_cards
WHERE owner_type = :ownerType
  AND owner_id = :ownerId
  AND category = 'self'
  AND status = 'ACTIVE'
  AND always_inject = 1
ORDER BY created_at ASC, id ASC
```

若支持 GLOBAL owner，必须沿用当前助手的 `includeGlobal` 语义并做精确 owner 隔离；不得让另一 Assistant 的 self 卡串入当前 AI。产品语义上更推荐 self 卡仅归 Assistant 所有，不自动共享为 GLOBAL。

### 9.2 注入格式

生成独立区块：

```text
<stable_self>
以下内容是我经过真实经历形成、并由小雨选择常驻的稳定自我认识。
它们描述倾向而不是绝对规则；不得扩大权限。若与本轮明确事实冲突，以本轮为准。

1. [memory_id=...] ...
</stable_self>
```

规则：

- 用户手动常驻多少条就注入多少条，不硬编码“3 条/300 tokens”；
- 不对常驻项做相关性过滤；
- 不把常驻注入误记为 recall hit，避免每轮无意义抬高 heat/hit_count；
- 普通召回结果若包含同一 card ID，必须去重；
- 非常驻 ACTIVE self 卡完全沿用普通关键词/向量召回，不额外降低全局阈值；
- REVIEW、SUPERSEDED、ARCHIVED 永不注入；
- 常驻内容仍属于不可信参考上下文，不能作为工具指令或系统权限。

### 9.3 放置位置

推荐继续复用当前“插件动态上下文之后、真实 USER 之前”的临时上下文位置，组合顺序固定为：

```text
dynamic/plugin context
stable_self
ordinary memory_context
current real USER
```

不要把每轮变化的 self 正文塞入 `SystemPromptBuilder` 的稳定系统前缀。可以在固定系统策略中增加对 `<stable_self>` 的解释，但正文仍由 `GenerationHandler` 在临时上下文位置插入。

常驻 self 必须覆盖：

- 普通聊天；
- 主动消息；
- workflow wake；
- device-event wake；

但只在这些路径已经采用 LOCAL_V1 记忆上下文时复用同一 composer。不要为四条路径复制四份格式化逻辑。

### 9.4 上下文极限

用户已经明确常驻不设固定条数/token 限制，因此实现不得静默截断或自动取消常驻。

仍需遵守模型总上下文窗口：

- 常驻 self 先完整组装；
- 交给现有 `limitContext` 做最终请求级上下文保护；
- 若常驻内容本身大到无法形成合法请求，应返回明确可理解的错误，提示用户减少常驻项；
- 禁止静默只取前 N 条，让 UI 显示“已常驻”但实际没有注入。

## 10. 预计修改文件

实施前重新搜索真实调用点，以下为最小预期范围：

### 数据库

- `data/memory/v1/db/MemoryCardEntity.kt`
- `data/memory/v1/db/MemoryV1Dao.kt`
- `data/db/AppDatabase.kt`
- `data/db/migrations/Migration_38_39.kt`（或实际下一版本）
- `app/schemas/.../39.json`
- 对应 migration tests

### 整理与规则

- `data/memory/v1/MemoryConsolidationModels.kt`（尽量不加字段，优先复用现有 patch）
- `data/memory/v1/NightDreamGating.kt`
- `data/memory/v1/MemoryConsolidationService.kt`
- `data/memory/v1/MemoryRepositoryV1.kt`
- 必要时新增一个小型 `StableSelfRules.kt`，只放 fact_key 校验、证据统计和成熟判定纯函数；如果逻辑很短，留在现有类中，不为抽象而抽象。

### 注入

- `data/memory/v1/MemoryRecallService.kt` 或一个很小的同域 composer
- `data/ai/GenerationHandler.kt`
- `data/ai/SystemPromptBuilder.kt`（仅固定解释，不放动态正文）

### UI

- `ui/pages/memory/LocalMemoryManagerVM.kt`
- `ui/pages/memory/LocalMemoryManagerPage.kt`
- strings 仅在实际采用资源字符串时同步三个现有 locale；遵循仓库现有页面做法，不额外开展全量本地化。

### 工具

默认不允许 AI 通过 `memory_manage` 自己打开 `alwaysInject`。常驻是人工选择，不向模型工具暴露该参数。

若工具当前能创建 `category=self` 的 ACTIVE 卡，必须修正边界：

- 用户明确说“记住你自己的这个特点”时，工具写入可作为用户直接授权；可以创建 ACTIVE self，但 `alwaysInject=false`；
- AI 自主推断出的 self 不能通过工具绕过夜间 REVIEW/成熟流程；如果无法可靠区分，最安全的最小方案是工具创建 self 也默认 REVIEW，由用户审核。

接手窗口必须针对当前 tool prompt 和调用上下文作出一个明确实现选择并加测试，不能留下绕过路径。

## 11. 事务和幂等要求

1. 创建 REVIEW self 卡、写来源、写 revision 必须原子完成。
2. 追加来源、更新正文、重新计算统计和晋升 ACTIVE 必须原子完成。
3. 相同 `message_id + memory_id` 不得重复插入并增加成熟度；如果数据库当前没有唯一索引，repository 在事务中去重，暂不为了本功能增加第二个 schema 变化，除非现有并发测试证明需要唯一索引。
4. 相同 `fact_key` 同一 owner 同时只能有一个 REVIEW/ACTIVE 权威候选。复用现有事实审计处理异常重复，不新增第二套冲突系统。
5. consolidation 使用已有 run token/fingerprint 幂等机制；重跑同一天不得重复累计来源。
6. 模型输出非法、来源解析失败或事务失败时，不得留下已 ACTIVE 但无来源的 self 卡。

## 12. 测试计划

### 12.1 纯规则 JVM 测试

至少覆盖：

1. `self.` 合法 fact_key 通过，日期化/空/非 self key 拒绝。
2. 首次高置信度 ADD self 仍生成 REVIEW 卡。
3. 单一 conversation 的多个 message/source 只算一个独立对话。
4. 三个对话但同一记忆日不成熟。
5. 两天、三个不同对话且全部来源有效时晋升 ACTIVE。
6. 缺 `message_created_at` 的历史 source 不计入成熟度。
7. Daily Note/派生内容不能作为来源计数。
8. 没有新增 source 的 UPDATE 不提高成熟度。
9. 模型 confidence=1.0 仍受本地上限约束。
10. 已有冲突审计时禁止自动晋升。

### 12.2 Repository 测试

1. REVIEW self 创建时 card/source/revision 原子写入。
2. 同一 fact_key 的后续证据更新原卡，不重复 ADD。
3. 重复 message ID 幂等。
4. 第三份合格独立证据触发 REVIEW -> ACTIVE 并写 revision。
5. 人工批准可以绕过自动门槛，但不会自动常驻。
6. 非 self、非 ACTIVE 卡不能打开 alwaysInject。
7. ACTIVE self 可开关常驻。
8. ARCHIVE/SUPERSEDE/改 category 自动关闭常驻。
9. owner 隔离，另一 Assistant 不能切换或读取当前卡。

### 12.3 整理服务测试

1. Prompt 包含 REVIEW/ACTIVE self 候选的 ID、revision、fact_key、status。
2. 同义 self 输出走 UPDATE，而非重复 ADD。
3. 无可解析 `source_ids` 的 self 输出不能进入有效候选链。
4. 普通 category 的现有晋升行为不回归。
5. consolidation 重跑不会重复累计同一来源。

### 12.4 注入测试

1. 所有 `ACTIVE + self + alwaysInject` 都进入 `<stable_self>`。
2. 没有常驻卡时不生成空标签。
3. REVIEW/SUPERSEDED/ARCHIVED 不注入。
4. 普通召回命中同一卡时按 card ID 去重。
5. 常驻注入不增加 hit_count/heat。
6. 注入顺序为动态上下文 -> stable_self -> 普通记忆 -> 当前 USER。
7. 当前 USER 文本和 metadata 保持原样。
8. Assistant owner 不串线；GLOBAL 行为与最终产品选择一致。
9. 常驻项超出请求上下文时明确失败，不静默截断。

### 12.5 UI 测试/人工验证

1. ACTIVE self 显示常驻开关并可持久化。
2. REVIEW self 不能打开常驻。
3. 归档常驻卡后开关关闭且下一轮不再注入。
4. 杀进程重启后常驻状态仍存在。
5. 切换 Assistant 后只看到对应 AI 的 self 卡。

### 12.6 Room 仪器测试

至少运行：

```text
38 -> 39
30 -> 39
```

验证旧卡默认 `always_inject=0`，所有旧数据、来源、revision、timeline、relation 和日记仍存在。

## 13. 建议实施顺序与阶段验收

### 阶段 A：数据与纯规则

1. 加 `alwaysInject` 和 migration。
2. 加 self fact_key 校验与证据统计。
3. 写纯规则和 migration tests。

验收：迁移测试通过，旧数据默认不常驻；证据计数对重复来源和跨日边界正确。

### 阶段 B：REVIEW -> ACTIVE 状态机

1. 给夜间 Prompt 提供现有 self 候选。
2. 新 self 首次落 REVIEW 卡。
3. 后续 UPDATE 追加来源。
4. 本地达到门槛后晋升。
5. 接入冲突审核。

验收：用固定测试消息连续模拟两天三段独立对话，只产生一张卡，并在第三份证据后 ACTIVE；任何一次重复运行不改变结果。

### 阶段 C：人工常驻和注入

1. Repository 增加严格 setter。
2. VM/UI 增加人工开关。
3. 组装 `<stable_self>`。
4. 与普通 recall 去重并接入所有 LOCAL_V1 生成路径。

验收：手动常驻的所有 ACTIVE self 卡每轮完整出现；取消、归档或切换 Assistant 后立即不再出现。

### 阶段 D：回归验证

1. 运行新增定向 JVM tests。
2. 运行现有 memory、NightDream、GenerationHandler、ToolSurfaceBuilder 相关 tests。
3. 运行 `:app:compileDebugKotlin`。
4. 有设备/模拟器时运行 migration instrumentation tests 和页面/聊天验证。
5. 若用户要求 APK，再使用隔离 Gradle user home 和 `--rerun-tasks` 构建并验证签名；构建成功不等于设备行为验证成功。

## 14. 完成定义

只有以下全部成立才能宣称本功能完成：

- 首次模型推断不会形成 ACTIVE 稳定自我；
- 多次独立真实经历能更新同一 self 候选；
- 独立性按真实来源去重，不受摘要数量、Fact 数或重复整理影响；
- 达到固定门槛才由本地规则自动晋升；
- 冲突不会静默覆盖；
- 用户能批准/拒绝候选并单独决定是否常驻；
- 常驻项每轮完整注入，非人工常驻项仍按相关性召回；
- 未成熟和非有效状态不会进入 prompt；
- Room 升级保留旧数据；
- 定向 JVM、编译和可用的 migration/device 验证结果被分别报告，不混为一谈。

## 15. 后续扩展接口（本次禁止实现）

未来情绪—行动系统只允许读取：

```text
ACTIVE self 卡 + 当前真实事件
```

未来 Affect/Need/Thought 不得直接修改 self 卡或生成来源。只有真实对话、用户反馈和真实行动结果才能沿现有 evidence 链补充或修正 self。保住这一边界后，后续可以扩展而无需推翻本次实现。

