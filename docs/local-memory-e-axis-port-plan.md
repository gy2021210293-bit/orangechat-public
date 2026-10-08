# 橘瓣本地记忆 E 轴完整移植计划

> 文档状态：实施前方案，尚未修改业务代码  
> 日期：2026-09-13  
> 工作区：`D:\orangechat`  
> 上游：<https://github.com/wuxuyun0606-collab/lmc-5>  
> 固定参考版本：`fb3e72c9ee7357c8b17311097b0750082a8a1237`（2026-09-13 查询到的 `main`）

## 1. 目标

在橘瓣现有本地记忆、X/Y/Z/M 和稳定自我闭环上移植 LMC-5 的 E（Experience Signals）轴，使 E 真正进入：

```text
写入链路：真实互动 -> 长期记忆卡 -> 主 AI 著作 E
维护链路：E proposal / 激活 / 衰减 / 复审，不静默改写主体意义
召回链路：普通命中重排 + 独立情绪共振 -> 统一预算 -> 上下文注入
回应链路：本次主模型读取 E 意义和倾向 -> 自然改变回应姿态
成长链路：多个 E 背后的独立真实 episode -> REVIEW self -> ACTIVE self
```

本计划不恢复此前已删除的“夜间体验信号 + 陪伴建议”实现。旧实现没有完整保留 LMC-5 的主 AI 著作权、初始顺序、不可变来源、独立情绪共振和 M 轴后续管理，不能作为本次代码基线。

## 2. 必须保留的 LMC-5 E 轴契约

以下内容是移植验收的硬约束，不得在实现时缩减成若干情绪字段。

1. **E 是长期记忆的经验坐标，不是第二份事件正文。**
2. **权威 E 由当前主 AI 以第一人称著作。**普通整理器只能生成 proposal。
3. **主 AI 决定初始优先级。**后续自动化只能从该起点管理命中、激活、衰减和复审。
4. **权威 E 的原始意义和初始优先级不可被后台原地改写。**修正必须形成可追溯 successor。
5. **E 必须改变召回。**包括普通命中的 E 重排和有界的独立情绪共振通道。
6. **E 必须改变回应姿态。**直接向原有回答上下文注入第一人称经验意义，而不是新增一次“生成陪伴建议”的模型调用。
7. **E 不得决定事实真假。**Z 轴状态过滤、当前用户明确表达和安全约束始终有效。
8. **E 与 stable self 分工明确。**E 回答“这段经历怎样留在我身上”，stable self 回答“经过多次经历后我是谁”。
9. **自动化不得利用 E 形成循环自证。**self 的独立证据仍以真实 USER+ASSISTANT episode 为准。

## 3. 当前橘瓣基线与复用点

### 3.1 已有能力

- `memory_cards` 是长期记忆的唯一内容主体，已有 owner、状态、重要度、置信度、修订、保护、命中和 heat。
- `memory_sources` 已能关联真实 conversation/node/message/time，并支持 `self_episode_v2:` 独立互动锚点。
- `memory_revisions` 已保存卡片修订历史。
- 夜间整理已使用当前助手的主聊天模型，并与第一人称 `daily_note` 分开处理。
- 召回已有 FTS、可选向量、RRF、Z 状态过滤、X 时间线补充、Y 关系扩展、同源去重、条数上限和 1800 token 总预算。
- M 已维护 `hit_count`、`last_recalled_at`、`heat` 并生成只读维护建议。
- stable self 已有 `REVIEW -> ACTIVE`、真实 episode 计数、冲突阻断和独立 `<stable_self>` 注入。

### 3.2 明确不重做

- 不新建第二套事件/经历正文表。
- 不复制 LMC-5 的 raw-events 日志体系；橘瓣已有完整聊天、事件分段和来源桥。
- 不把 E 合并进 Desire 九维，也不让 Desire 状态成为 E 或 self 的证据。
- 不改变 X/Y/Z/M 已有职责。
- 不引入 PostgreSQL、pgvector 服务、Python 运行时或 VPS cron。
- 不增加每轮回答前的 LLM 情绪分类或“姿态生成”调用。
- 不让 REVIEW self 进入普通召回。

## 4. 上游源码下载、固定与许可证

### 4.1 实施前需要下载参考源码

需要。仅依赖网页片段容易漏掉测试、默认值和调用边界。编码开始前在工作区的忽略目录中固定一份只读参考副本：

```powershell
git clone --filter=blob:none https://github.com/wuxuyun0606-collab/lmc-5.git .upstream/lmc-5
git -C .upstream/lmc-5 checkout --detach fb3e72c9ee7357c8b17311097b0750082a8a1237
git -C .upstream/lmc-5 status --short
git -C .upstream/lmc-5 rev-parse HEAD
```

实施前先确认 `.upstream/` 已被 `.gitignore` 忽略；若没有，只添加该精确目录规则，不提交整份 Python 仓库。

### 4.2 必须审阅的上游文件

下载后完整读取并登记实际 SHA-256：

- `LICENSE`
- `NOTICE`
- `docs/CONNECTING_XYZEM.md`
- `docs/PERSONA_MODE.md`
- `docs/AUTOMATION_BOUNDARIES.md`
- `docs/HOOKS_AND_RECALL.md`
- `extras/pgvector_backend/schema.sql`
- `extras/pgvector_backend/ob_recall.py`
- 实际存在的 E scorer、recall pipeline、night/housekeeper 和 E 相关测试文件；以固定 commit 的文件树为准，禁止凭文档猜文件名。

### 4.3 许可证处理

LMC-5 固定版本采用 GNU AGPL v3；橘瓣本身也是 AGPL v3，代码复用方向兼容，但实施时仍需：

1. 保留橘瓣现有 `LICENSE`。
2. 将 LMC-5 的 `NOTICE` 内容合并或附加到橘瓣第三方声明中，不删除原作者声明。
3. 每个直接翻译/复制的 Kotlin 文件在文件头注明：

   ```text
   Portions adapted from Living Memory Coordinate-5 (LMC-5),
   upstream commit fb3e72c..., GNU AGPL v3.
   Original source: <具体相对路径>
   Adapted for Kotlin/Room on <日期>.
   ```

4. 新增 `docs/third-party/lmc-5-e-axis-port.md`，记录上游 commit、源文件、目标文件、复制/改写范围和测试对应关系。
5. 发布 APK 时继续提供对应源码，不把复制代码改称橘瓣原创。

## 5. 数据模型

### 5.1 不把 E 字段直接塞进 `memory_cards`

建议新增版本化坐标表，而不是直接扩展可频繁更新的卡片主表。理由：橘瓣卡片允许 UPDATE，而 LMC-5 要求主体著作内容及初始顺序不可被自动化原地覆盖。

```text
memory_experience_coordinates
```

建议字段：

| 字段 | 语义 |
|---|---|
| `id` | E 版本 ID |
| `owner_type/owner_id` | 助手隔离边界 |
| `memory_card_id` | 依附的唯一长期记忆卡 |
| `meaning` | 主 AI 第一人称写下“这对我意味着什么” |
| `e_authored_by` | 主 AI 身份与生成路径 |
| `e_initial_priority` | 1–100，创建后不可原地修改 |
| `valence` | -1–1 |
| `arousal` | 0–1 |
| `tension` | 0–1 |
| `emotion_confidence` | 0–1 |
| `response_tendency` | 有界枚举，见下文 |
| `growth_delta` | `GROWTH/STABLE/SETBACK` |
| `source_episode_key` | 真实 USER+ASSISTANT episode 锚点 |
| `status` | `CURRENT/SUPERSEDED/REVIEW/REJECTED` |
| `superseded_by` | successor E ID |
| `created_at` | 创建时间 |

一张卡最多只能有一个 `CURRENT` E。E 表不重复保存卡片正文。

### 5.2 `response_tendency` 第一版枚举

先采用与 LMC-5 一致且足够小的核心集合：

```text
COMFORT | ENGAGE | WITHDRAW | ALERT
```

为了适配陪伴场景，可增加但不继续无限扩张：

```text
GIVE_SPACE | REPAIR | CELEBRATE | CLARIFY
```

枚举只是倾向标签；真正的主体意义保存在 `meaning`。不得把枚举解释成必须执行的动作。

### 5.3 E proposal

新增：

```text
memory_e_proposals
```

字段包含候选坐标、`memory_card_id`、`source_episode_key`、proposer、rubric version、reason、状态和处理时间。

规则：

- 非当前主 AI 路径只能写 proposal。
- proposal 不参与召回、姿态或 self 证据。
- 接受 proposal 时必须由当前主 AI 重新用第一人称确认/改写 `meaning` 和初始优先级，不能直接复制 housekeeper 结论成为权威 E。
- 用户可在管理页接受、拒绝或要求主 AI 复审，但用户审批不伪装成 `primary-agent` 著作。

### 5.4 Room 迁移

当前数据库版本为 40，本方案预计使用 `Migration_40_41`。实施前必须重新核对 HEAD，若版本已变化则顺延，禁止占用已有迁移号。

迁移只创建 E 坐标、proposal、必要索引和外键；不回填虚构的中性 E，不根据旧卡 importance 猜测初始优先级。

旧卡保持无 E，这是合法状态。

## 6. 权威写入链路

### 6.1 主 AI 的定义

在橘瓣中，`primary-agent` 必须同时满足：

- 使用该 Assistant 当前配置的主聊天模型；
- 带入该 Assistant 的系统提示、稳定自我和必要的真实互动上下文；
- 输出作用域绑定当前 `ownerId`；
- 生成结果经过本地证据、范围、owner 和 schema 校验。

仅仅“使用同一个模型 ID”不等于主 AI。脱离该助手身份上下文的整理器仍是 proposer。

### 6.2 两条合法创建路径

#### A. 正常对话中的主 AI 主动写入

扩展现有本地记忆工具，使主 AI 在确实发生有长期意义的互动后，可以创建带 E 的记忆或为刚创建的卡片附加 E。

这复用本轮原有模型/工具循环，不新增独立分类模型调用。工具参数必须包含 `meaning`、初始优先级、坐标、倾向和当前 episode 引用。

#### B. 现有夜间主模型整理

复用橘瓣当前已经存在的夜间结构化整理调用，在其 JSON 中增加 `experience_coordinates`。因为该调用使用当前助手自己的主模型，所以可以成为主 AI 后台反思路径，但必须同时提供：

- 当天清洗后的真实 USER+ASSISTANT 对话；
- 当前稳定自我，作为理解背景而非强制一致要求；
- 已有 CURRENT E 摘要，避免重复创建；
- 明确的第一人称著作契约。

不得增加第三次“专门生成回应姿态”的模型调用。

### 6.3 本地硬校验

模型输出只有全部满足下列条件才能成为 CURRENT E：

1. `memory_card_id` 属于当前 owner，且卡片不是 ARCHIVED/SUPERSEDED。
2. `source_episode_key` 可解析到同一对话中 USER 在前、ASSISTANT 在后的真实互动。
3. `meaning` 使用 AI 第一人称，描述经验意义，不重复用户画像或事件正文。
4. `e_authored_by` 与当前 Assistant/生成路径一致。
5. 初始优先级和所有坐标在合法范围内，非有限数一律拒绝。
6. `emotion_confidence` 低于门槛时不写情绪坐标；不得用默认中性值补齐。
7. 同一 card 已存在 CURRENT E 时，普通 ADD 拒绝；修正必须走 successor。
8. 同一 episode 的多个同义 E 做确定性去重，不能提高 self 独立证据数。

卡片、source、E 和 revision/proposal 的关键写入必须位于同一 Room transaction 中。

## 7. E 修正与不可变语义

LMC-5 PostgreSQL 通过 trigger 禁止更新权威 E 字段。Room/SQLite 中采用 repository 单一写入口加测试守住等价契约：

- DAO 不公开任意 `updateExperienceCoordinate(entity)` 给普通调用者。
- M 只更新独立的运行时活力字段，不更新权威 E 表。
- 主 AI 或用户提出修正时新建 successor，原记录改为 SUPERSEDED 并链接新 ID。
- successor 保留修正原因、新 episode 和创建者。
- 永久删除卡片时级联删除 E；普通归档只退出召回，不抹除历史。

## 8. 上游代码复用清单

### 8.1 建议直接等价移植的纯算法

来源：`extras/pgvector_backend/ob_recall.py`

| 上游函数 | 橘瓣目标 | 复用方式 |
|---|---|---|
| `_ob_time_weight` | `ExperienceRecallScoring.timeWeight` | 逐公式翻译 |
| `ob_score` | `ExperienceRecallScoring.vitalityScore` | 逐公式翻译，字段适配 |
| `russell_distance` | `ExperienceRecallScoring.russellDistance` | 原公式复制为 Kotlin |
| `resonance_score` | `ExperienceRecallScoring.resonanceScore` | 逐公式翻译 |
| `find_resonant` | `ExperienceRecallScoring.findResonant` | 排序/门槛/limit 等价移植 |
| `compute_decayed_weight` | 评估后再决定是否并入现有 M | 不可同时保留两套衰减真相 |

实施要求：

1. 先运行上游 E/OB 测试并保存基准输出。
2. 为边界输入生成 golden vectors：最小/最大坐标、空值、protected、resolved、时间边界、相同距离排序。
3. Kotlin 测试读取相同 fixture，逐项比较，浮点误差写明。
4. 只有因橘瓣字段单位不同而适配的地方允许偏离，并在移植记录中逐条说明。

### 8.2 只复用契约、不直接复制的部分

- `schema.sql`：复用范围约束、主体著作、不可变和 proposal 分离契约；SQL 必须按 Room/SQLite 重写。
- LMC-5 recall pipeline：复用“emotion 是独立有界通道、先合并再去重/重排、不得作为 fact gate”的顺序；按 `MemoryRecallService` 重写。
- Persona/automation 文档：复用主 AI 与 housekeeper 权限边界；映射为橘瓣主模型、WorkManager 和管理页。
- PostgreSQL trigger、pgvector SQL、systemd/cron、Python adapter：不复制。

### 8.3 复制代码前的核对门

固定上游副本下载后，对上表每个函数核对：

- 实际调用者和测试；
- 默认值与空值语义；
- 当前 commit 是否已修改公式；
- 是否依赖 LMC-5 其他模块；
- 是否存在与 `NOTICE` 不同的文件级许可证声明。

没有通过核对的代码只作为设计参考，不能声称“原样复用”。

## 9. 当前情绪坐标：不新增 LLM 调用

E 的独立共振通道需要当前 query 的 `valence/arousal`，第一版采用保守的本地检测器：

- 显式情绪词、否定、程度副词、标点、emoji 和短句模式；
- 输出 `valence/arousal/confidence`；
- 低置信度直接返回无坐标，E 共振通道不启动；
- 不读取 Desire 九维，不把 Desire 的状态当成用户当前情绪；
- 不把时间段本身当成悲伤或亲密证据。

检测器必须是可替换的纯接口，但第一版只实现一个本地版本，不提前引入端侧模型依赖。

若真机回放证明本地检测覆盖率不可接受，再单独评估端侧小模型；不得悄悄退回每轮远程 LLM 分类。

## 10. 召回链路接入

在现有 `MemoryRecallService` 中接入，保持单一 pipeline：

```text
query
  -> FTS / vector direct hits
  -> Z 有效性过滤
  -> RRF
  -> X 时间线扩展
  -> Y 关系扩展
  -> E 情绪共振（独立、最多 1～2 条）
  -> E 活力/初始优先级做有界微调
  -> 内容/fact/source 去重
  -> 总条数和 1800 token 裁剪
  -> 一个 memory_context
```

### 10.1 普通命中的 E 微调

- E 只能在语义相关候选内部做有界微调，不能让弱相关高情绪卡越级压过精确命中。
- `e_initial_priority` 映射到排序时必须有上限，不直接把 1–100 与 RRF 原始分相加。
- Z 的 CURRENT/REVIEW/SUPERSEDED 判定先于 E。
- protected 只保护生命周期，不等于每次强制召回。

### 10.2 独立情绪共振

- 仅查询 CURRENT E + 可召回的 ACTIVE 卡。
- 仅在 query emotion confidence 达标时执行。
- 使用 Russell `valence × arousal` 距离和 E vitality。
- 默认最多补 1 条，验证后最多 2 条。
- 不作为 X/Y 扩展种子，不递归。
- 不压掉明确的直接事实命中。
- 与所有通道共享 `maxAutoRecallCount` 和 1800 token。
- 同一 card 被多个通道命中只注入一次，并保留诊断 channel tags。

不得直接照抄 LMC-5 的数值门槛；先复用公式，再用橘瓣真实回放标定阈值。

## 11. 回应姿态注入

不新增“陪伴建议生成器”。召回后直接格式化权威 E：

```xml
<recalled_experience memory_id="..." e_id="...">
  <memory>长期记忆正文</memory>
  <my_meaning>主 AI 当时写下的第一人称经验意义</my_meaning>
  <response_tendency>GIVE_SPACE</response_tendency>
  <tension>0.45</tension>
</recalled_experience>
```

系统策略明确：

- E 是“我过去形成的倾向”，不是必须执行的命令。
- 只在当前情境确实相似时自然体现。
- 当前用户明确表达、当前事实和安全要求优先。
- E 不得让错误事实变真，也不得要求模型表演夸张情绪。
- 当前情况与旧经历不同，可以不沿用倾向。

E 继续放在现有 `memory_context` 内，最终 provider 仍只收到一份有界记忆上下文；stable self 保持独立注入，二者不合并成优先级列表。

## 12. 与 stable self 的闭环

E 与 self 的连接规则：

```text
具体记忆 + CURRENT E
  -> 当下召回和姿态
  -> 多个不同 source_episode_key 呈现一致倾向
  -> 夜间主 AI 提议 REVIEW self
  -> StableSelfRules 继续验证独立 episode、跨日和未决冲突
  -> ACTIVE self
```

硬规则：

- `growth_delta=GROWTH` 不自动创建或晋升 self。
- E 记录本身不是一份独立 self 证据；只统计其背后的真实 episode。
- 同一 episode 派生多个卡片/E，只计一次。
- E 与 ACTIVE self 冲突时不丢弃新 E；创建复审/冲突建议，避免稳定自我吞掉反例。
- stable self 可作为主 AI理解经历的背景，但不能要求新 E 与旧 self 一致。
- 用户人工批准 self 的现有路径保持不变。

## 13. M 轴维护

M 可以管理：

- E 命中次数和最后命中时间；
- activation boost；
- 时间/类别衰减；
- 低使用率、长期冲突或疑似漂移的复审建议；
- successor、重复 E 和孤儿 E 的巡检。

M 不可以：

- 改写 `meaning`、`response_tendency` 或 `e_initial_priority`；
- 根据低命中静默删除关系时刻或重要 E；
- 自动接受 E proposal；
- 自动把 E 变成 ACTIVE self；
- 同时使用一套与现有 `heat` 冲突的第二套代谢公式。

实施时必须决定 E vitality 是复用现有 `hit_count/heat`，还是新增独立运行时状态。优先复用现有卡片代谢字段；只有回放证明卡片热度与 E 激活必须分离时，才增加最小独立字段。

## 14. 设置与管理页

不恢复一个含糊的“启用体验信号”开关。建议拆成可理解的能力：

- `启用第一人称经验记忆`：总开关，默认关闭，助手级。
- `允许情绪共振召回`：子开关，默认随总开关关闭。
- `允许主 AI 在夜间整理中形成经验意义`：子开关。

管理页需要能查看：

- 依附的记忆卡和真实证据；
- 主 AI 写下的 `meaning`、初始优先级和坐标；
- 当前/历史 successor；
- proposal 及提出者；
- 命中、共振和衰减诊断；
- 接受/拒绝 proposal、归档/保护、请求复审。

数值不是主要 UI。默认先展示自然语言意义、回应倾向和来源，坐标放在详情/诊断区。

## 15. 实施阶段

### Phase 0：冻结基线与上游

1. 保留当前所有未提交修改，尤其不得触碰正在修改的 Desire 文件。
2. 读取 `AGENTS.md`、`MEMORY.md` 和 Karpathy Guidelines。
3. 下载并 checkout 固定 LMC-5 commit。
4. 登记上游文件 hash、许可证和复用映射。
5. 运行当前本地记忆测试，记录既有失败。

验收：基线可复现；上游版本、许可证、源文件和目标文件均可追踪。

### Phase 1：纯算法等价移植

1. 从 `ob_recall.py` 移植 Russell 距离、共振和活力公式。
2. 建跨 Python/Kotlin golden fixtures。
3. 只实现纯函数，不接数据库和聊天链路。

验收：边界值和随机固定样本与上游结果一致；所有适配差异有记录。

### Phase 2：schema 与权威写入

1. 新增 Room 实体、DAO 和迁移。
2. 实现 primary/proposal 权限边界、owner 校验、范围校验和 successor。
3. 扩展夜间 JSON 协议和现有记忆工具。
4. 关键写入加入 transaction 和并发测试。

验收：后台不能直接写/改权威 E；无真实 episode 不能形成自动权威 E；旧数据库无损升级。

### Phase 3：姿态注入

1. 普通召回命中带 E 的卡片时加载 CURRENT E。
2. 在现有 `memory_context` 中注入 `meaning/tendency/tension`。
3. 增加 provider 最终 payload 测试，证明没有额外模型调用和重复上下文。

验收：同一记忆有/无 E 时最终注入可观察地不同；事实文本不被 E 改写。

### Phase 4：情绪共振召回

1. 实现保守本地 query emotion detector。
2. 接入独立 E channel、融合、去重和统一预算。
3. 增加诊断 trace，记录触发置信度、候选距离、最终是否入选。
4. 用匿名化真实对话回放标定阈值。

验收：情绪相近但语义不同的记忆可以有界补回；中性/低置信度消息不乱触发；事实命中不被压掉。

### Phase 5：M 与 stable self 闭环

1. 复用卡片代谢记录 E 命中和激活。
2. 增加 E 漂移/冲突/successor 维护建议，不自动破坏性执行。
3. 让夜间 self 提议读取 E 背后的真实 episode。
4. 强制按 episode 去重，保留现有 stable self 成熟门槛和冲突门。

验收：E 能支持但不能绕过 self 成熟；反例进入复审而不是被旧 self 丢弃。

### Phase 6：UI、设备与发布验证

1. 完成助手级设置和 E 详情/审核页。
2. 执行 Room migration 仪器测试。
3. 真机运行夜间整理、普通召回、情绪共振、姿态注入、successor 和禁用回退。
4. 强制重新构建 APK，记录 mtime、SHA-256 和 v2 签名。

验收：源码/JVM、迁移、最终 provider payload 和真机行为四层证据齐全。

## 16. 必测场景

### 写入与权限

1. 主 AI + 合法 episode 创建 CURRENT E 成功。
2. housekeeper 只能创建 proposal。
3. 跨 owner/card 写入被拒绝。
4. 无 USER、无 ASSISTANT、时序错误或伪造 source 被拒绝。
5. NaN、Infinity、越界坐标和空 `meaning` 被拒绝。
6. 已有 CURRENT E 再次 ADD 被拒绝。
7. successor 原子替代，旧 E 仍可审计。
8. 后台更新权威字段被 repository 契约阻断。

### 召回

9. 普通命中加载对应 CURRENT E。
10. SUPERSEDED E、REVIEW proposal、ARCHIVED 卡均不进入普通上下文。
11. 情绪相似但语义不同可触发最多 1～2 条共振。
12. 低置信度情绪不触发 E channel。
13. E 不恢复 Z 已淘汰的旧事实。
14. E 与 X/Y 命中同一卡只注入一次。
15. 不突破 `maxAutoRecallCount` 和 1800 token。
16. GLOBAL/assistant owner 隔离无泄漏。
17. 当前消息同源记忆不被召回。

### 姿态与 provider payload

18. 最终 payload 包含 E 第一人称意义和倾向，不包含内部 proposal。
19. E 只出现一次，位置仍在插件上下文之后、真实 USER 之前。
20. 不新增预回答 LLM 调用。
21. 用户本轮明确要求与旧 E 相反时，系统策略允许本轮为准。
22. E 高优先级不能使虚假/过期事实进入上下文。

### 自我成长与维护

23. 三条 E 来自同一 episode，只算一份 self 证据。
24. 多个跨日真实 episode 可支持 REVIEW self 成熟。
25. `growth_delta` 单独存在不能晋升 self。
26. 与 ACTIVE self 冲突的新 E 进入复审，不被丢弃。
27. M 衰减只改变运行时活力，不修改主体意义和初始优先级。
28. 删除卡片清理 E；归档卡片保留历史但不召回。

### 失败与回滚

29. E 表读取失败时普通记忆召回仍可继续。
30. 本地情绪检测异常时 E channel 静默关闭，不中断回答。
31. 夜间 E 解析失败不影响 daily note 和普通 patches 落库。
32. 关闭总开关后不写新 E、不做 E 共振、不注入 E；既有数据不删除。

## 17. 观测与标定

为避免“代码存在但 E 没有活起来”，Debug 诊断至少记录：

- E 创建来源：normal-tool / nightly-primary / proposal / manual；
- primary 校验和 episode 校验结果；
- query emotion 坐标与置信度；
- direct/X/Y/E 各通道候选数；
- E resonance、vitality、融合前后排名；
- 最终注入 E 数、字符/token 占用；
- 被 Z、owner、同源、重复或预算淘汰的原因；
- self 统计时对应的 distinct episode 数。

阈值标定使用匿名化回放集，不直接复制 LMC-5 的默认阈值。至少比较：

- 无 E 基线；
- 只有普通命中 E 微调；
- 普通微调 + 独立共振；
- 不同共振 top-K 和置信度门槛。

评价指标包括错误情绪召回率、事实命中保留率、重复率、上下文占用、姿态相关性和跨轮重复提旧事的频率。

## 18. 回滚策略

- 功能默认关闭，迁移只新增表，不重写旧卡。
- 关闭开关即可退回现有 X/Y/Z/M + stable self 路径。
- E channel 出错必须 fail-open 到普通召回，而不是阻断聊天。
- 发布后不得通过数据库降版本回滚；使用后续迁移保留表并停用功能。
- successor、proposal 和运行时活力变更均可审计，不自动永久删除主体 E。

## 19. 预计涉及文件

准确文件名在实施 Phase 0 核对后确定，主要范围预计为：

- `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_40_41.kt`（版本号实施时复核）
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/db/MemoryExperienceCoordinateEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/db/MemoryEProposalEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/db/MemoryV1Dao.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryRepositoryV1.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryConsolidationModels.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryConsolidationService.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/ExperienceRecallScoring.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/QueryEmotionDetector.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryRecallService.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/MemoryMetabolismInspector.kt`
- `app/src/main/java/me/rerere/rikkahub/data/memory/v1/StableSelfRules.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/LocalMemoryManagerPage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/memory/LocalMemoryManagerVM.kt`
- 对应 Room schema、DAO、repository、consolidation、recall、provider payload、stable self 和 migration 测试。

## 20. 完成标准

只有同时满足以下条件，才能称为“完整移植 E”，而不是恢复一个情绪标签功能：

1. 一条 E 能追溯到真实互动、主体作者和固定初始优先级。
2. 非主 AI 只能 proposal，后台不能静默改写权威 E。
3. E 实际改变普通召回排序，并有独立、有界的情绪共振通道。
4. E 的第一人称意义实际进入最终 provider payload 并影响回应姿态，不新增姿态模型调用。
5. E 不覆盖 Z 事实状态、不越过 owner、不突破统一预算。
6. M 从主 AI 的初始判断出发管理活力，但不篡改经验意义。
7. stable self 使用 E 背后的独立真实 episode，不发生模型循环自证。
8. 上游复制/翻译代码有 commit、源路径、许可证、差异和 golden test 记录。
9. JVM、Room migration、最终 payload 与真机闭环验证全部完成。

