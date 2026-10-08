# 本地记忆 X 召回与 E 移除方案

> 文档状态：实施方案，尚未修改业务代码  
> 日期：2026-09-10  
> 工作区：`D:\orangechat`

## 1. 目标

在不追求完整复刻 LMC-5 五轴的前提下，完成两项收敛：

1. 删除尚未随正式 APK 发布的 E 轴体验信号实现，降低模型误判和长期维护成本。
2. 让现有 X 时间线参与自动召回，使时间线不再只服务管理页和夜间整理。

最终保留的职责是：

- X：从已命中的记忆出发，补充同一持续事项的少量上下文。
- Y：维持现有一跳安全关系扩展。
- Z：维持现有事实演化、冲突审计和当前事实过滤。
- M：维持现有代谢记录、巡检建议和管理页处理入口。

## 2. 术语边界

- `memory_event_chunks` 是夜间整理内部使用的聊天分段材料，不是面向用户的“事件演化线”，不参与本次改名或重构。
- X 时间线由 `memory_timelines` 和 `memory_timeline_members` 表示，用来归组同一持续事项下的多张长期记忆卡。
- Z 事实演化由 `fact_key`、卡片状态和 `memory_fact_audits` 表示，已经完成，不属于本次 X 改造。
- 每日日记的 `emotional_thread` 只是当天日记摘要的一部分，不等于 E 轴体验信号，本次必须保留。

## 3. 当前基线

### 3.1 已有且不重做

- 普通召回已经包含关键词、可选向量、状态过滤、同源去重、事实键去重、数量上限和 token 预算。
- Y 已能以普通召回命中的卡片为种子，执行一跳安全关系扩展。
- Z 已能发现同一 `fact_key` 的冲突，进入审核，并在确认后执行事实替代或其他处理。
- M 已在召回后更新 `hit_count`、`last_recalled_at` 和 `heat`；夜间巡检会生成维护建议。
- 本地记忆管理页已经展示候选提议、事实冲突、关系审核和代谢巡检建议，并提供处理动作。本次不再新增第二个“待处理建议”页面。

### 3.2 当前缺口

- X 的时间线成员关系没有进入 `MemoryRecallService`，因此不会改变下一轮模型实际收到的记忆。
- E 会由夜间主聊天模型写入体验信号，并在召回时附加“陪伴建议”；这部分没有不可替代的召回价值，却增加了 schema、模型协议、设置和提示词维护。

## 4. 变更 A：完整移除 E 轴

### 4.1 删除范围

删除以下 E 专属内容：

- `MemoryExperienceSignalEntity.kt`。
- `MemoryV1Dao` 中体验信号的增删查接口。
- `MemoryRepositoryV1` 中体验信号保存、查询和删除逻辑，以及永久删除卡片时的相关清理。
- `MemoryConsolidationModels` 中 `ExperienceSignalDto` 和 patch 的 `experience` 字段。
- `MemoryConsolidationService` 中体验信号提示词、解析、清洗和落库逻辑。
- `NightDreamGating` 中仅供 E 使用的体验信号清洗逻辑。
- `MemoryRecallResult`、`MemoryRecallService` 和记忆上下文格式化中的体验信号、陪伴建议及标签。
- `Assistant.enableExperienceSignals` 配置字段及 `AssistantMemoryPage` 对应开关。
- `LocalMemoryManagerVM/Page` 中体验信号读取与展示。
- E 专属单元测试以及其他 Fake DAO 中为 E 添加的空实现。

不得删除：

- `MemoryDailyNoteEntity.emotionalThread`。
- 每日日记界面中的情绪脉络展示。
- 主模型根据当前对话自行调整语气的普通能力。

### 4.2 Room 版本处理

当前 E 表仅由未发布的 `38 -> 39` 迁移引入。既然用户确认没有发布包含 E 的 APK，采用发布前回退，而不是永久保留一条无意义的删除迁移：

1. 从 `AppDatabase` 的实体列表删除 `MemoryExperienceSignalEntity`。
2. 数据库版本从 39 回退为 38。
3. 从数据库构建/DI 注册处移除 `Migration_38_39`。
4. 删除 `Migration_38_39.kt`、schema `39.json` 和对应 schema 校验测试。
5. 保留 30→38 的全部既有迁移和 schema，不改写已经完成的 X/Y/Z/M 历史。

开发机上若安装过数据库版本 39 的内部 Debug APK，回退后的 APK 无法直接打开高版本数据库，需要卸载 Debug App 或清除其本地数据后重新安装。由于版本 39 尚未正式发布，不为这种内部数据额外增加 `39 -> 40 DROP TABLE` 的永久维护负担。

### 4.3 E 移除验收

- 全仓库搜索不再出现 `MemoryExperienceSignal`、`enableExperienceSignals`、`memory_experience_signals`、`response_tendency`。
- 夜间整理 JSON 协议不再要求或接受 `experience`。
- 召回上下文中不再出现“陪伴建议”标签。
- 每日日记的 `emotional_thread` 仍可解析、保存和显示。
- Room v38 schema 与 `AppDatabase` 实体一致，30→38 迁移链继续通过。

## 5. 变更 B：让 X 时间线参与自动召回

### 5.1 触发原则

X 不作为独立全库搜索通道。只有关键词或向量已经命中长期记忆卡时，才允许从这些种子卡所属的 ACTIVE 时间线补充成员。

这样可避免普通问候或弱相关查询仅凭时间线标题拉入整段历史。

### 5.2 扩展规则

新增一个职责单一的 repository 方法，例如：

```text
expandTimelines(seedCardIds, ownerType, ownerId, includeGlobal, limit = 2)
```

规则如下：

1. 只读取种子卡实际加入的时间线。
2. 时间线必须为 ACTIVE，且 owner 必须属于当前助手或明确启用的 GLOBAL 范围。
3. 候选成员卡必须为 ACTIVE；SUPERSEDED、REVIEW、ARCHIVED 一律不能自动注入。
4. 排除种子卡自身、当轮同源卡片、重复内容和重复 `fact_key`。
5. 按成员时间优先选择离当前阶段最近的卡片；时间相同时按卡片 `updatedAt` 降序。
6. 全部时间线合计最多补充 2 张卡，不做递归，不沿 Y 关系继续扩张。
7. 某个时间线或成员读取失败时只跳过 X 扩展，不能中断普通聊天。

### 5.3 与现有召回额度的关系

继续服从 `maxAutoRecallCount` 和 1800 token 总预算，不额外扩大注入量。

默认上限为 8 时采用以下优先级：

1. 普通关键词/向量直接命中：最多 5 张。
2. X 同时间线补充：最多 2 张。
3. Y 一跳关系补充：最多 1 张。
4. 某个扩展通道没有结果时，用后续普通直接命中回填空位。

当 `maxAutoRecallCount` 小于 3 时，不预留 X/Y 扩展额度，优先保证直接命中。现有 `enableGraphExpansion` 只控制 Y，不复用它控制 X，也不新增 X 设置开关；LOCAL_V1 自动召回开启时，存在有效时间线成员才自然生效。

### 5.4 输出标记

当前 `expandedCards` 只表达 Y 关系扩展，不能继续混装。将召回结果区分为：

- `timelineExpandedCards`：X 补充。
- `graphExpandedCards`：Y 补充。

格式化后的内部提示分别标记为“同一事项的进展”和“关联记忆”。标签仅供模型理解，不在聊天 UI 中展示。

### 5.5 X 召回验收场景

至少覆盖：

1. 普通搜索命中卡 A，A 属于 ACTIVE 时间线，补回同线最近的 ACTIVE 卡 B。
2. 同线存在 SUPERSEDED、REVIEW、ARCHIVED 卡时均不补回。
3. A 不属于任何时间线时，召回结果与修改前一致。
4. 多条时间线同时命中时，X 总量仍不超过 2。
5. X 与 Y 命中同一卡时只保留一次，并优先标记为 X 事项进展。
6. X 补充卡与当前用户消息同源时被排除。
7. `maxAutoRecallCount` 和 1800 token 上限不会被突破。
8. GLOBAL 时间线在未启用共享记忆时不能越权进入助手召回。
9. 时间线 DAO 异常时普通 FTS/向量结果仍可正常注入。
10. 最终 provider 消息仍只有一个 `memory_context`，位置保持在插件上下文之后、当前真实 USER 之前。

## 6. 实施顺序

### Phase 1：先删除 E

1. 先调整 Room schema 和模型协议。
2. 删除 repository、service、UI 和设置依赖。
3. 删除或改写受影响测试夹具。
4. 验证 v38 schema、Kotlin 编译和现有本地记忆测试。

删除 E 后先形成一个可编译、可测试的干净基线，再开始 X 召回，避免两类改动同时造成测试定位困难。

### Phase 2：增加 X 召回

1. 先为 X 扩展规则补失败测试。
2. 添加最小 DAO/repository 查询能力。
3. 接入 `MemoryRecallService` 的额度、去重和格式化流程。
4. 补 provider 消息顺序回归测试。
5. 运行本地记忆相关 JVM 测试与 Kotlin 编译。

### Phase 3：设备验证

在 Android 设备或模拟器上建立一条包含至少三张 ACTIVE 卡片的时间线，验证：

- 相关问题能补回 1～2 条同线进展。
- 无关问题不会触发时间线召回。
- 管理页仍能查看时间线、事实审核、关系审核和代谢建议。
- 夜间整理不再生成 E 信号，但每日记忆日记仍正常出现。

## 7. 预计涉及文件

主要修改范围：

- `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_38_39.kt`（删除）
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/db/MemoryExperienceSignalEntity.kt`（删除）
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/db/MemoryV1Dao.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryRepositoryV1.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryConsolidationModels.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryConsolidationService.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/NightDreamGating.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryRecallService.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/LocalMemoryManagerPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/LocalMemoryManagerVM.kt`
- 对应的 schema、迁移测试、召回测试、整理测试和 Fake DAO。

## 8. 明确不做

- 不修改已经完成的 Z 事实演化语义。
- 不把 `memory_event_chunks` 暴露到 UI 或当作第二套事件演化线。
- 不做 X 独立全文搜索、整条时间线注入或递归扩展。
- 不把 Y 从一跳扩大到两跳。
- 不新增 E 的替代情绪模型、情绪向量或情绪召回。
- 不新增第二个维护建议页面。
- 不借本次改动重构旧记忆、外置记忆或无关聊天代码。

## 9. 完成标准

只有同时满足以下条件才可称为完成：

- E 代码、设置、schema 和提示词完全移除，每日日记情绪摘要保持正常。
- 普通命中能够受限地补回同一 X 时间线的有效进展。
- Z 事实过滤、Y 一跳扩展、M 巡检与管理入口无回归。
- 所有扩展共同服从原有条数及 token 上限。
- JVM 测试和 Kotlin 编译通过。
- 真机或模拟器完成至少一次 X 正向召回和一次无关查询不召回验证。
