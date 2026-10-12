<div align="center">

<img src="docs/icon.png" alt="橘瓣" width="112" />

# 橘瓣 OrangeChat

**在原有 OrangeChat 基础上继续扩展的 Android AI 助手**

把记忆、主动交互、插件和日常工具带进聊天。

</div>

---

## 项目简介

本仓库基于 [OrangeChat](https://github.com/sue1231513/orangechat) 继续开发。OrangeChat 本身承接了 [RikkaHub](https://github.com/rikkahub/rikkahub) 的 Android AI 客户端基础；本分支保留多模型聊天和原有生活工具，并重点扩展本地记忆、助手主动交互、自动化工作流、插件运行时与共同活动。

## 相对原版 OrangeChat 的改动

### 1. 助手关系、状态与主动交互

- 新增情感状态与关系事件处理，让助手能在后续交流中参考双方明确表达过的关系事件。
- 扩展主动消息机制，加入独立的欲望状态、调度策略、后台唤醒和活动记录。
- 支持在聊天中管理助手的自主事项；事项管理与后台唤醒调度分开处理。
- 主动消息、后台工具和插件能力提供相应的设置与授权控制。

### 2. 独立的本地记忆 V1

- 新增本地记忆卡片、语义召回、时间线与关系视图、经历记录和每日整理能力。
- 支持检查记忆来源与演变，并由用户审核整理提议。
- 本地记忆作为独立能力加入，与既有对话记忆及外部记忆配置并存。

### 3. 插件运行时与宿主协作

- 完善 QuickJS 插件沙箱、插件工具注册、配置项、权限声明与生命周期管理。
- 扩展声明式原生 UI 和 WebView 页面能力，支持插件定时任务、通知及 AI 唤醒等宿主动作。
- 加强插件与当前聊天会话的衔接；支持符合模型能力的图片附件输入。

### 4. 自动化工作流

- 增加可配置触发器、后台执行与唤醒流程，支持按计划或设备事件触发。
- 加入执行去重、状态记录和恢复处理，方便查看工作流及后台活动的执行情况。

### 5. 共同听歌与分享内容阅读

- 新增网易云音乐共听能力，可在应用内管理播放会话、查看歌单和歌曲，并向聊天提供当前播放信息。
- 扩展聊天中的网页与小红书分享内容读取，支持提取正文及图片等内容供助手理解。
- 分享图片按当前模型的输入能力处理；不支持图片的模型仍可使用文本内容。

### 6. 模型与交互体验改进

- 持续修正 OpenAI、Google 等接口的流式响应、多模态内容和工具调用兼容性。
- 改进 MCP 工具调用时的 OAuth 令牌刷新与连接恢复。
- 增加记忆管理、记忆关系图、主动活动记录、工具启动入口和历史记录固定等界面能力。

## 开源项目与移植来源

部分功能参考或移植自其他开源项目。下表区分了实际移植与设计参考；这不表示整套项目直接复制。

| 项目 | 在橘瓣中的用途 | 采用方式与许可 |
|------|----------------|----------------|
| [LMC-5](https://github.com/wuxuyun0606-collab/lmc-5) | 本地记忆 V1 的 XYZEM 架构思路及经历记忆 | 部分算法与数据契约移植并改写为 Kotlin / Room；相关部分为 AGPL-3.0-or-later。参考版本与文件映射见 [移植登记](docs/third-party/lmc-5-e-axis-port.md)。 |
| [AstrBot Desire System](https://github.com/Yussica1026/AstrBot-Desire-System-) | 助手欲望状态、演化与主动发送策略 | 相关逻辑按 Android、Room 和多助手场景改写；上游为 MIT，固定参考版本见 [来源映射](docs/third-party/astrbot-desire-system.md)。 |
| [affective-longing](https://github.com/pearthink123/affective-longing) 与 [revive-companion](https://github.com/pearthink123/revive-companion) | 情感状态、关系阶段，以及主动联系时机与效用判断 | 移植部分算法并用 Kotlin 重写；两个上游项目均为 MIT。具体算法和固定参考版本见 [情感引擎来源说明](app/src/main/java/me/rerere/rikkahub/data/ai/affect/UPSTREAM.md)。 |
| [affectus](https://github.com/n-yokomachi/affectus) 与 [jev-dev](https://github.com/n-yokomachi/jev-dev) | 多轴情绪状态设计、Jev 与 LLM 判定对照思路 | 作为设计参考。橘瓣的 `JevEventClassifier` 独立调用可选的 [TypeSafe Jev API](https://api.typesafe.ai/docs)，并在不可用时回退本地规则；未直接集成这两个项目的运行时。affectus 为 MIT。 |
| [ncm-listen-together](https://github.com/wuxiandudang-hash/ncm-listen-together) | 网易云「一起听」房间邀请与状态协议参考 | 仅参考房间协议；橘瓣的播放器、播放会话和 Android 媒体服务由本项目原生实现，上游项目为 MIT。 |

上述模块保留各自的上游来源与许可证说明。使用或再分发时，请同时查看对应上游许可证、仓库内的移植登记和源码声明；本节不替代这些文件。

## 沿用的 OrangeChat 能力

本分支继续保留并迭代原版已有的能力，包括：

- OpenAI、Google、Anthropic 等模型供应商及自定义接口配置
- MCP、联网搜索、消息分支、助手配置和富文本消息展示
- 图片、文档等多模态输入，以及 PDF、DOCX、PPTX 文档处理
- 原有的位置与附近搜索、Gadgetbridge 健康数据、应用使用信息等生活工具
- 原有的同步与插件生态

以上列表用于说明项目继承关系；具体能力会受模型接口、服务配置、系统权限及设备支持影响。

## 技术栈

- **Android / Kotlin**：应用与业务逻辑
- **Jetpack Compose / Material 3**：原生界面
- **Room / DataStore**：本地数据与偏好设置
- **QuickJS**：插件 JavaScript 运行时
- **Ktor**：内嵌 Web 服务
- **Media3**：音频播放
- **Gradle 多模块**：AI、搜索、文档、语音、工作区等功能模块

## 项目结构

```text
app/          Android 应用、聊天、记忆、工作流和插件宿主
ai/           模型供应商与消息协议
search/       搜索能力
document/     文档解析
speech/       语音能力
workspace/    工作区相关能力
common/       通用组件
web/          内嵌 Web 服务
web-ui/       Web 前端
plugins/      随仓库提供的插件
```

## 构建

### 环境要求

- Android Studio
- JDK 17
- Android SDK；项目当前使用 `compileSdk 37`，最低支持 `minSdk 26`

### 命令

```bash
./gradlew :app:assembleDebug
./gradlew test
```

## 源码与致谢

- 本仓库：[gy2021210293-bit/orangechat-public](https://github.com/gy2021210293-bit/orangechat-public)
- 上游 OrangeChat：[sue1231513/orangechat](https://github.com/sue1231513/orangechat)
- RikkaHub：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)

## 许可证

本项目基于 [OrangeChat](https://github.com/sue1231513/orangechat)，而 OrangeChat 又基于 [RikkaHub](https://github.com/rikkahub/rikkahub)。RikkaHub 标注为 AGPL-3.0；本仓库保留 OrangeChat 的分段双重许可文本，两者不能视为相同许可证。

- **非商业 / 个人 / 教育 / 研究**，或**用户总数不超过 10 人**的组织：原许可文本列明可遵循 [GNU AGPL v3](https://www.gnu.org/licenses/agpl-3.0.html)，并承担相应义务。
- 商业用途、用户超过 10 人，或希望免除 AGPL v3 义务：原许可文本要求另行取得商业授权；本仓库维护者不能代表上游授予该授权。

详见 [LICENSE](LICENSE)。此处仅用于说明仓库现有许可文本，不表示不同上游条款之间的兼容问题已得到解决。
