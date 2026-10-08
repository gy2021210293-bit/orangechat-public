# LMC-5 E 轴移植登记与许可证声明

- **上游项目**：Living Memory Coordinate-5 (LMC-5)
- **上游地址**：https://github.com/wuxuyun0606-collab/lmc-5
- **固定参考 Commit**：`fb3e72c9ee7357c8b17311097b0750082a8a1237` (2026-09-13)
- **开源许可证**：GNU Affero General Public License v3.0 (AGPL-3.0-or-later)
- **橘瓣开源许可证**：GNU Affero General Public License v3.0 (AGPL-3.0-or-later)

## 上游核心文件 SHA-256

| 文件路径 | SHA-256 |
|---|---|
| `LICENSE` | `E0EEDBA615D5CD1B986AFB6C5B3A4B1AE33713E7E9DC74D19DAEC5E3221F9D2E` |
| `NOTICE` | `066730CE6D99854C3342AAB1E26EA4002688DDBBF114802052B50FE4D5B833EA` |
| `extras/pgvector_backend/ob_recall.py` | `2ED9968F926CD385AB2007CA99ECD5F2D75FD27D630083C7469A508AA332BF4F` |
| `extras/pgvector_backend/schema.sql` | `07A1B2303596050DC43534243B2EC83C2E3551123B21275A6CE9B35BE224EBAF` |
| `extras/pgvector_backend/migrations/20260804_primary_agent_owns_e_axis.sql` | `5F7D0DF3CDD0A678310E662760E4314EBF2CFACFAEB2FF5F375F11F1C1B33BEE` |
| `docs/CONNECTING_XYZEM.md` | `4269193E14C0666A0A6000C1624858B7E83A2975FE1A1769196A65C5E6D7B07A` |
| `docs/PERSONA_MODE.md` | `25F9D480D1445B12D7C2CFF98E92C7A136710D1CC38A27B09159192F8F953314` |
| `docs/AUTOMATION_BOUNDARIES.md` | `7F52FD968F2B0FECCD7D9BA7EA050611F578D42DEE3E68ADCBD2AAB19C87A2AC` |
| `docs/HOOKS_AND_RECALL.md` | `7BEE6586AA78DFCAF169ADF4D3C94865C3AD85A5365BBA042882748ED2161D8D` |
| `tests/test_e_axis_ownership.py` | `532BC1F86396E4DA3BD916613844C9D0D8F9C4D70390836FB11BA23758C0D09E` |

## 移植与复用映射表

| 上游文件 / 概念 | 橘瓣目标文件 | 复用方式与说明 |
|---|---|---|
| `ob_recall._ob_time_weight` | `me.rerere.rikkahub.data.memory.v1.ExperienceRecallScoring.timeWeight` | 逐公式翻译（0-1天=1.0，第2天线性降到0.9，之后指数衰减底限0.3） |
| `ob_recall.ob_score` | `me.rerere.rikkahub.data.memory.v1.ExperienceRecallScoring.vitalityScore` | 逐公式翻译，适配字段命名与默认值 |
| `ob_recall.russell_distance` | `me.rerere.rikkahub.data.memory.v1.ExperienceRecallScoring.russellDistance` | 原欧式距离 / sqrt(2) 翻译为 Kotlin |
| `ob_recall.resonance_score` | `me.rerere.rikkahub.data.memory.v1.ExperienceRecallScoring.resonanceScore` | 逐公式翻译：(1.0 - dist) * (1.0 + vitality / 10.0) |
| `ob_recall.find_resonant` | `me.rerere.rikkahub.data.memory.v1.ExperienceRecallScoring.findResonant` | 排序、过滤门槛与限制截断等价移植 |
| `schema.sql` / `20260804_primary_agent_owns_e_axis.sql` | `MemoryExperienceCoordinateEntity.kt`, `MemoryEProposalEntity.kt`, `Migration_40_41.kt` | 提取 E 主 AI 著作与 proposal 分离契约，使用 Room/SQLite 重写，建立版本化独立表 |
| `recall_pipeline.py` (emotion_resonance adapter) | `MemoryRecallService.kt` | 融合进橘瓣单一 recall 管线，不覆盖 Z 轴状态，受统一 1800 Token 预算控制 |
| E 回应姿态 | `MemoryRecallService.kt` (`<recalled_experience>`) | 直接注入第一人称经验意义与倾向，不增加额外姿态生成 LLM 调用 |

## 原作者声明 (NOTICE)

```text
LMC-5 — Living Memory Coordinate-5
Copyright (c) 2026 wuxuyun0606-collab

Version 0.3.0 and later:
  GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)
```
