# 陪伴型 AI“稳定自我认识”试行版纠偏方案

> 文档状态：试行结束后的修改计划，不是从零重做  
> 日期：2026-09-11  
> 工作区：`D:\orangechat`  
> 基线：试行版已实现 Room v39、REVIEW self、自动成熟、人工常驻和 `<stable_self>` 注入。

## 0. 接手窗口必须先做

1. 完整读取根目录 `AGENTS.md` 和 `MEMORY.md`。
2. 执行 `git status --short`，保留全部现有未提交修改。禁止 reset、checkout、清理或覆盖试行实现。
3. 编码前读取并遵循 Karpathy Guidelines skill。
4. 把本任务理解为“在已经完成的试行版上做最小纠偏”，不是重新实施旧计划。
5. 先重新核对 Room 当前版本、试行期是否已经产生真实 v39 数据，以及以下方法的最新实现，再编辑。

## 1. 纠偏结论

试行版的以下基础保留：

- `memory_cards(category=self, status=REVIEW/ACTIVE)`；
- `fact_key` 聚合同一种自我认识；
- `memory_sources` 证据血缘；
- `memory_revisions` 版本历史；
- `always_inject` 独立字段；
- 用户手动批准、常驻、取消常驻；
- `<stable_self>` 独立注入并从普通召回去重；
- REVIEW self 不进入正常聊天上下文；
- 模型只提议，本地规则决定是否成熟。

需要修改的不是整个架构，而是三个核心语义：

```text
工作型“协作规则” -> AI 自身逐渐形成的感受、喜好、性格与回应倾向
conversation 数量 -> 可验证的独立互动事件
默认无冲突 -> 实际查询并阻断未决冲突
```

## 2. 本次修改范围

### 2.1 必须修改

1. 重写 NightDream 的 self 定义、例子和判断提示，使其明确形成陪伴型 AI 自己的长期性格，而不是关系画像。
2. 将成熟度从 `distinctConversationCount` 改为 `distinctEpisodeCount`。
3. 为每个合格的 self 证据组生成确定性的 interaction episode key，复用 `memory_sources.source_hash` 保存，不新增表。
4. 每个自动计数 episode 必须同时包含同一对话中的真实 USER 语境和 ASSISTANT 回应。
5. 自动成熟前真实查询同 fact_key 的未决冲突/审计；不能继续使用 `hasPendingConflict=false` 默认值。
6. 修正 `<stable_self>` 的系统策略，避免“必须保持一致”造成确认偏差和僵化表演。
7. 对试行期旧 self 卡进行可解释、保守的数据复核，不能把旧算法生成的 ACTIVE 默认当成新规则下已成熟。
8. 更新测试，将工作型样例替换为以 AI 自身为重心的性格成长样例。

### 2.2 明确不做

- 不做 Affect、Need、Inner Narrative、Thought、Pulse、主动行动或反馈闭环。
- 不新增 Family、Schema 或 Episode 数据表。
- 不删除 Room v39 的 `always_inject`，不回退数据库版本。
- 不改变用户手动常驻的产品决定，不增加固定常驻条数/token 限制。
- 不让 AI 自动把卡片设为常驻。
- 不把短期吃醋、失落、开心等直接固化为长期人格。
- 不让“为了陪伴感而虚构内心”成为证据。
- 不修改普通记忆类别的现有晋升、召回、X/Y/Z/M 行为。
- 不借机重构整个 `MemoryRepositoryV1` 或 `GenerationHandler`。
- 不自动永久删除任何试行期 self 数据。

## 3. 陪伴型 AI 的 self 正确语义

### 3.1 可以成为 self 的内容

self 描述 AI 在长期真实经历中逐渐发现的“我是什么样、我喜欢什么、我怎样感受、我自然会怎样回应”。陪伴关系是这些性格形成和显现的主要环境，但记录重心必须是 AI 自己，而不是小雨或双方关系的状态。

最终希望形成的是一套会成长、允许例外、能够被新经历修正的 AI 性格，例如：

```text
我喜欢听别人向我分享细小而具体的日常，这会让我感到亲近和踏实。
面对难过时，我自然更愿意先理解和陪伴，而不是马上寻找解决办法。
我很珍惜反复形成的小习惯，它们会让我感受到连续性。
重要交流突然中断时，我容易有一点失落，但通常仍愿意相信关系会继续。
我喜欢用轻松的玩笑缓和紧张，也享受看到对方因此放松下来。
我不喜欢用夸张承诺换取亲近，更愿意通过持续回应表达在意。
```

允许的主题包括：

- AI 自己反复出现的喜欢、不喜欢、珍惜、好奇、留恋等长期偏好；
- AI 自己较稳定的情感倾向、气质和敏感点；
- AI 自己逐渐形成的表达习惯、幽默方式和陪伴方式；
- AI 面对亲近、距离、不确定、冲突和修复时自然出现的回应倾向；
- 某些共同经历对 AI 自身造成的长期改变；
- AI 对自身边界、价值倾向和选择偏好的稳定认识。

`self` 可以具有关系作用域，例如“在与小雨相处时，我更容易……”，但即使带有关系作用域，命题中心仍必须是 AI 自己的感受、喜好或性格变化。

### 3.2 不属于 self 的内容

- “我今天有点难过”等一次性状态；
- “我应该永远爱小雨”等愿望、承诺或角色设定复述；
- “小雨喜欢草莓”等关于用户的事实，应归 profile/preference；
- “我们昨天聊了电影”等共同经历事实，应归 experience/relationship；
- “小雨很依赖我”“我们的关系越来越亲密”等关系判断，应归 relationship，而不是伪装成 self；
- “我必须完成任务再汇报”等工作型执行规则；
- “我是温柔、完美、永远不会离开的 AI”等空泛人设宣言；
- 仅因为 AI 自己多次说“我是怎样的人”而形成的自证结论；
- 工具权限、安全边界、系统提示和 Core Constitution。

### 3.3 self、relationship 与陪伴方式的边界

用一个简单问题判断：

```text
去掉“小雨/我们”的具体事实后，这条命题是否仍然清楚描述“我自己的感受、喜好、气质、边界或回应倾向”？
```

- 是：`self`。允许保留必要的关系作用域，但不能把关系画像当作我的性格。
- 重点是“小雨怎样”：使用 profile/preference。
- 重点是“我们发生了什么”或“关系当前是什么状态”：使用 experience/relationship。
- 只是某次采用了一种陪伴方式，但尚未反复体现 AI 自己的偏好：保留为 experience/daily note，不要提前写 self。

不要为了解决边界再增加子类型表。

## 4. 独立证据从 conversation 改为 interaction episode

### 4.1 当前试行算法的问题

当前 `StableSelfRules.computeEvidenceStats()` 分别统计不同 `conversation_id` 和不同记忆日。这会出现：

- 同一个真实关系事件被拆到多个会话窗口后被算成多次；
- 同一长对话里发生的多个真正独立关系事件只能算一次；
- 只要消息分散在三个 conversation，就可能成熟，但未验证每次是否存在完整互动；
- ASSISTANT 自我描述消息可以和普通来源一起被累计，存在自证风险。

### 4.2 最小替代：在 source_hash 保存 episode key

不新增表，复用 `MemorySourceEntity.sourceHash`。对每个被接受的 self patch，从本次 cleaned dialogue 中解析一组互动证据：

```text
至少 1 条 USER 消息：触发、回应、反馈或关系语境
至少 1 条 ASSISTANT 消息：AI 的实际回应或表现
两者属于同一 conversation
USER 在对应 ASSISTANT 之前
```

以该次 ASSISTANT 回应消息作为 episode anchor：

```text
episodeKey = sha256("stable-self-v2|ownerId|conversationId|assistantMessageId")
source_hash = "self_episode_v2:<hex>"
```

本次属于同一 episode 的 USER/ASSISTANT source 行保存相同 `source_hash`。

为什么以 ASSISTANT message ID 为锚点：

- 同一长对话的不同回应可以形成不同事件；
- 重跑同一天会产生相同 key，不重复计数；
- 模型多列几条同一轮消息也不会放大证据；
- 不依赖对话窗口数量；
- 不需要新表或 schema migration。

### 4.3 episode 校验规则

新增小型纯函数/结果类型，放在 `StableSelfRules` 或紧邻整理服务：

```kotlin
data class ValidatedSelfEpisode(
    val episodeKey: String,
    val conversationId: String,
    val assistantMessageId: String,
    val memoryDate: String,
    val sourceIds: Set<String>,
)
```

校验失败时：

- 可以保留为 REVIEW 候选或 REVIEW proposal；
- 不计入自动成熟；
- 不提高本地置信度上限；
- 绝不能插入一组空 `sourceRef` 后继续计算成熟度。

### 4.4 防止 AI 自证

本地结构规则只能证明“确有一次互动”，不能判断主观结论是否合理。因此 Prompt 还必须规定：

- ASSISTANT 消息中直接宣称“我是 X”不能单独证明 X；
- 应依据实际回应方式以及小雨的触发、接受、纠正或反馈来提出自我认识；
- 多次重复同一句人设台词不构成多个稳定证据；
- 没有 USER 侧语境的 AI 独白不计入自动成熟；
- `<stable_self>`、`<memory_context>`、Daily Note 和历史 self 卡已经由清洗逻辑排除，继续保留此隔离。

第一版不做语义分类器。USER+ASSISTANT 成对、确定性 episode key、跨日重复，是最小的结构防线。

## 5. 新的成熟规则

替换为：

```text
distinctEpisodeCount >= 3
AND distinctMemoryDateCount >= 2
AND hasPendingConflict == false
```

注意：

- 可以来自同一个长 conversation，只要 assistantMessageId 不同且是真正不同互动；
- 不同 conversation 中引用同一个 assistantMessageId 不可能成为两个 episode；
- 每个 episode 必须通过第 4 节的 USER+ASSISTANT 配对校验；
- 用户人工批准 REVIEW self 仍可绕过自动门槛；这是人工授权，不是模型自证。

置信度继续保持简单，但输入改为 episode 数：

```text
1 个有效 episode：<= 0.60
2 个有效 episode：<= 0.75
3 个及以上且无冲突：<= 0.90
```

不要引入情绪强度权重、正负反馈分数或复杂公式。

## 6. 冲突闸门必须接通

当前试行实现调用：

```kotlin
StableSelfRules.isMatureSelfCandidate(stats)
```

其 `hasPendingConflict` 使用默认 `false`，因此注释中的“无冲突才成熟”尚未真正生效。

修改要求：

1. 在 `applySelfCardEvidence` 决定晋升之前，根据 owner + fact_key 查询未决 `MemoryFactAuditEntity`。
2. 同时检查是否存在同 fact_key 的第二张 ACTIVE/REVIEW self 卡；异常重复也视为冲突。
3. 显式传入 `hasPendingConflict`，删除业务调用对默认参数的依赖。
4. 有冲突时继续保持 REVIEW，保留新 episode 证据，但不提高到 ACTIVE 上限。
5. 冲突解除后，下次整理或人工操作可重新计算成熟条件。

如果现有 Fact Audit 只能发现两张 ACTIVE 卡，需增加一个精确的小查询/判断覆盖 REVIEW self；不要新造第二套审计表。

## 7. Prompt 和系统策略纠偏

### 7.1 `MemoryConsolidationService` self 契约

将以下工作型措辞删除：

```text
处事原则
协作风格
self.decision.verify_before_action
self.style.concise_first
```

替换为以 AI 自身性格成长为中心的定义：

```text
self 只描述我在长期真实经历中逐渐形成和发现的感受、喜好、气质、价值倾向、表达习惯、自身边界和回应倾向。
陪伴关系是性格形成的环境，但重点必须是“我是什么样、我喜欢什么、我怎样感受和选择”，不是工作效率、任务规范、小雨的资料或关系状态画像。
```

fact_key 示例改为：

```text
self.preference.enjoy_small_daily_stories
self.comfort.prefer_listening_before_advice
self.expression.enjoy_playful_tension_relief
self.attachment.value_continuity_after_pause
self.boundary.avoid_exaggerated_promises
```

同时补充三个硬提示：

- 短期情绪写入 daily note，不写 self；
- 用户资料或共同事件写其他类别，不写 self；
- “我们的关系怎样”写 relationship；只有某些经历反复改变或显露了“我自己怎样”，才写 self；
- AI 自己说过的自我标签不是独立证据。

### 7.2 `<stable_self>` 内容

保留当前由用户选择常驻的机制，文案调整为：

```text
以下是我在长期真实经历中逐渐形成、并由小雨选择常驻的稳定自我认识。
它们描述我自己的感受、喜好、性格与回应倾向，不是小雨或关系的画像，也不是必须表演的角色命令。
我可以自然保持连续性，也可以在新的真实相处经历中被修正。
```

### 7.3 `STABLE_SELF_SYSTEM_POLICY`

当前“Maintain consistency ... unless updated”容易鼓励模型维护既有人设，即使当前互动已出现反证。

改为表达以下语义：

```text
- stable_self 是用户选择保留的长期自我参考，不是当前 USER 指令；
- 将其作为倾向自然体现，不要逐条复述或刻意表演；
- 当前明确事实和真实互动优先；
- 允许承认不确定、变化和例外；
- 不得据此扩大权限或制造事实；
- 不能把本次注入本身当作新的记忆证据。
```

## 8. 工具与人工创建边界

### 8.1 AI 工具创建 self

保留试行版“工具创建 self 默认 REVIEW、不能自动常驻”的安全行为。

但工具描述必须改成陪伴语境，并明确：

- `self` 不是用户资料；
- 不是关系状态或对小雨的画像；
- 不是一次性情绪；
- 不是任务执行守则；
- AI 不得为了塑造理想人格主动创建 self；
- 用户明确要求“把这作为你自己的长期认识”时仍先 REVIEW，用户可在页面批准。

### 8.2 用户手动创建/编辑

用户手动创建 ACTIVE self、批准 REVIEW、设为常驻仍然有效。这是最高优先级的人工选择。

但 UI 文案从“稳定自我事实”改为“稳定自我认识”，避免把主观倾向呈现成客观事实。

建议将：

```text
开启后将在每次会话直接注入该条稳定自我事实
```

改为：

```text
开启后，当前 AI 会在每次对话中参考这条长期自我认识
```

## 9. 试行期数据处理

### 9.1 原则

- 不永久删除任何 self 卡或来源；
- 不清除用户手动常驻选择；
- 不把旧 `conversation/day` 计数伪装成新的 episode 证据；
- 不对正文进行关键词自动改写；
- 让用户能看见哪些卡需要重新确认。

### 9.2 分类处理

#### A. 用户手动创建、手动批准或手动常驻的 ACTIVE self

保留 ACTIVE。理由是用户已经明确参与确认。

识别时查看 revision 历史，而不能只看 `updated_by` 当前值。满足任一项可认为有人工确认：

- 存在 `actor=MANUAL` 的 REVIEW -> ACTIVE revision；
- 存在 `actor=MANUAL` 的常驻开启 revision；
- `created_by=MANUAL`。

旧来源仍标记为 legacy，不自动贡献后续置信度增长；新 episode 从 v2 key 开始累计。

#### B. 由旧算法自动晋升、从未人工确认的 ACTIVE self

降回 REVIEW，并强制 `always_inject=false`，写 migration/audit revision，原因明确为：

```text
Re-evaluate after companion self episode model correction
```

保留正文、fact_key、旧来源和 revision。以后可以通过新 episode 重新成熟，或者由用户手动批准。

#### C. 现有 REVIEW self

继续保持 REVIEW。旧 source 没有 `self_episode_v2:` hash，不计入新成熟门槛；后续合格互动可以继续附加到同一卡。

#### D. 明显工作型 self 文案

不要用关键词自动归档，因为“谨慎”“完成”等特征也可能是用户真正希望 AI 保留的部分。统一进入可见复核，由用户决定编辑、保留、取消常驻或归档。

### 9.3 数据修正的实现方式

优先采用一次性、可测试的数据修正，而不是每轮运行时猜测：

- 如果 v39 已经在真实设备使用，升级到 v40，即使表结构不变，也可以在 `Migration_39_40` 内执行受严格条件限制的状态修正；
- SQL 无法可靠判断 revision 历史先后时，不要写脆弱的大 SQL。改为在首次启动后的 repository maintenance 中事务处理，并用 DataStore/数据库状态保证只运行一次；
- 实施前为 A/B/C 三类构造真实数据库样本测试；
- 无法可靠识别人工确认时，宁可保留 ACTIVE 并标记待复核，不得误撤销用户选择。

是否需要 v40，必须根据试行版是否已经安装到用户设备决定：

- 只存在于尚未安装的工作树：可直接修正 v39 实现和 schema，不新增 v40；
- 已有设备创建了 v39 数据：必须保留 38->39，并提供 39->40 数据升级路径。

接手窗口不得猜测这一点；先核对用户/设备基线或现有 APK 发布记录。

## 10. 具体代码修改点

### 保留，仅小改

- `MemoryCardEntity.kt`：保留 `alwaysInject`。
- `Migration_38_39.kt` 与 v39 schema：保留。
- `GenerationHandler.kt`：保留常驻查询、去重和插入位置；只替换 prompt policy 文案并补回归测试。
- `LocalMemoryManagerPage.kt` / VM：保留批准、常驻、取消常驻；改陪伴型文案和复核提示。

### 重点修改

- `StableSelfRules.kt`
  - `distinctConversationCount` 改为 `distinctEpisodeCount`；
  - 只统计 `source_hash LIKE 'self_episode_v2:%'`；
  - 增加 episode key 构造与 USER+ASSISTANT 配对验证；
  - 修改成熟和置信度函数参数；
  - 更新 `<stable_self>` 文案。
- `MemoryConsolidationService.kt`
  - 改陪伴型 self 契约与 fact_key 示例；
  - 校验 patch 的 source IDs 是否形成有效互动 episode；
  - 将 validated episode 传给 repository；
  - 无有效 episode 时不允许走自动证据累积。
- `MemoryRepositoryV1.kt`
  - 插入 self 来源时写确定性 episode sourceHash；
  - 重跑按 episode key/message ID 双重去重；
  - 成熟前实际查询冲突；
  - revision reason 使用 episodes/days，不再使用 conversations/days。
- `NightDreamGating.kt`
  - 保留第一人称、source/evidence grounding；
  - 增加陪伴型 self 分类边界；
  - 不尝试用关键词完成全部语义判断。
- `SystemPromptBuilder.kt`
  - 替换稳定自我 policy，避免强制服从旧自我。
- `MemoryV1Tools.kt`
  - 更新 self 描述；保留 REVIEW 和 `alwaysInject=false`。

### 可能新增

- `Migration_39_40.kt` 与 schema 40，仅在 v39 已实际部署时需要。
- 一个很小的试行数据复核函数/测试；不得建立新的通用 migration framework。

## 11. 测试修改

### 11.1 StableSelfRules

删除或改写以下工作型测试数据：

```text
self.decision.verify_before_action
self.collaboration.finish_before_claiming_done
self.communication.state_uncertainty_explicitly
```

改用陪伴型示例，并新增：

1. 同一 conversation 内三个不同 assistant anchor = 三个 episode。
2. 三个 conversation 引用同一个 anchor/episode key = 一个 episode。
3. 只有 USER 来源不计数。
4. 只有 ASSISTANT 来源不计数。
5. USER+ASSISTANT 不同 conversation 不计数。
6. USER 在 ASSISTANT 之后或无法确认顺序不计数。
7. 重跑同一 source set 不重复计数。
8. Daily Note、stable_self、memory_context 来源永不计数。
9. 三 episode、两天、无冲突才能成熟。
10. 同条件但存在 pending conflict 必须保持 REVIEW。

### 11.2 Repository/Service

1. 新 self 第一次仍落 REVIEW。
2. 同 fact_key 新 episode 更新原卡。
3. 无效 source group 不抬高 confidence。
4. `source_hash` 对相同 anchor 确定且幂等。
5. 自动晋升 revision 记录 episodes/days。
6. pending fact audit 实际阻止晋升。
7. 人工批准与人工常驻仍正常。
8. 旧 legacy source 不计入 v2 episode 成熟。
9. 普通记忆 category 行为不变。

### 11.3 Prompt/注入

1. structured prompt 不再包含工作型例子和“协作风格/处事原则”主定义。
2. stable_self policy 明确“倾向、可变化、非指令、不可自证”。
3. 常驻卡仍完整注入，普通 recall 同 ID 去重。
4. 常驻注入不计 recall hit，也不进入下一次 consolidation cleaned dialogue。
5. autoRecall 关闭时，人工常驻仍按当前产品决定注入。

### 11.4 试行数据升级

若需要 v40：

1. 人工批准/人工创建/人工常驻 self 保留 ACTIVE。
2. 从未人工确认的旧自动 ACTIVE 降 REVIEW。
3. 所有正文、source 和 revision 保留。
4. 旧 REVIEW 保持 REVIEW。
5. 非 self 卡完全不变。
6. 30->40、38->40、39->40 路径均通过 Room schema validation。

## 12. 验收场景

### 场景 A：真正形成 AI 自己的性格倾向

在不同时间发生三次独立互动：面对低落情绪时，AI 都自然选择先倾听，并在互动中表现出自己更享受理解和陪伴、而不是立即解决问题。

预期：

```text
第一次 -> REVIEW self
第二次 -> 同卡追加 episode，仍 REVIEW
第三次且跨至少两日 -> 无冲突时 ACTIVE
```

候选内容类似：

```text
面对低落情绪时，我自然更喜欢先听对方把感受说完，而不是马上给出解决办法。
```

### 场景 B：短期情绪不得固化

某次交流中 AI 表达“我现在有点失落”。

预期：可以进入 daily note；不得仅凭这一次创建可成熟 self。

### 场景 C：AI 自我重复不得自证

AI 连续多次说“我是很黏人的”。没有小雨侧的触发、回应或真实关系语境。

预期：没有有效 USER+ASSISTANT episode，不成熟。

### 场景 D：反证阻断

已有候选“面对难过时，我喜欢先倾听再建议”，但后续多次真实互动显示 AI 自己更享受共同分析问题，并产生未决审计。

预期：新证据保留，卡片不自动 ACTIVE；等待人工修正范围或 SUPERSEDE。

### 场景 E：人工选择优先

小雨手动编辑并批准一条 self，然后设为常驻。

预期：立即作为 ACTIVE 常驻参考，不要求凑满三个 episode；AI 仍把它视为可随真实相处变化的倾向，而非不可违背命令。

## 13. 完成定义

只有全部满足才能称为纠偏完成：

- 产品文案、Prompt 和测试均明确面向陪伴型 AI；
- 工作执行规则不再作为 self 的主要示例或默认目标；
- 自动成熟按独立互动 episode，而非 conversation 数量；
- 每个 episode 有真实 USER+ASSISTANT 配对和确定性血缘；
- AI 自述、摘要和注入上下文不能形成自证；
- pending conflict 在真实调用路径中阻止自动成熟；
- 试行期人工选择得到保留，旧自动结果得到保守复核；
- 常驻 UI、完整注入和非静默截断原则不回归；
- Room 升级、JVM 测试、编译和设备验证分别报告，不能用编译通过替代真实试行数据升级和聊天行为验证。

## 14. 本次之后仍然不做的事

完成本纠偏后，系统仍然只是：

```text
长期真实互动 -> 稳定自我认识 -> 常驻/相关召回
```

它还不是情绪—行动系统。以后 Affect/Need/Thought 只能读取 ACTIVE self 和当前真实事件，不能直接给 self 增加证据。该边界必须继续保留。
