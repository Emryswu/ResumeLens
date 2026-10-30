# Agent v2 运行登记

## 预登记计划

| 槽位 | 被测业务代码 | 规模 | 状态 |
| --- | --- | --- | --- |
| baseline | `c1c80c5ace3314de34949fd74e061dea8bb20990`（创建 v2 时的 main） | dev 9 题 + acceptance 9 题，每题独立重复 3 次 | 未运行，等待人工通知 |
| structured | 「学历/经验结构化」PR 合入后的 main | 同上 | 未运行，等待该 PR 合入 |

## 冻结前检查（不计入正式运行）

2026-10-26：在 baseline 业务代码上，用离线参考解跑通全部 dev 与 acceptance 任务（均 PASS），确认任务可解；未调用真实模型。#111 review 后，评分逻辑拆到 `AgentEvalV2Grading.java` 并纳入摘要，拆分后重新确认 acceptance 参考解均 PASS。摘要覆盖 `tasks/acceptance.json`、`snapshots/acceptance/` 和 `java/com/arthur/jdragresume/agent/AgentEvalV2Grading.java`，值为 `0b11fd025bc58d6bd130af5883419e020705c70f627915908cc9020cd7e9d351`，已写入 `AgentJobSearchEvalV2Tests.ACCEPTANCE_DIGEST`。

## 接线修复与 dev 参考解更新记录

- 2026-10-26，分支 `eval/v2-wiring-preferences`（#107 合入后、structured 运行前），由非结构化一方修复。原因：#107 把求职条件新增字段命名为 `educationLevel`、`currentlyStudent`、`expectedGraduationDate`，与 profile 中冻结的 `education`、`studentStatus`、`expectedGraduation` 不一致，原样反序列化时这三项被忽略。修复：`AgentEvalV2.preferencesView` 在反序列化前把 profile 字段映射到新字段名（学历按 专科/大专=1、本科=2、硕士/研究生=3、博士=4；在校状态含「在校/在读」为 true；年月取当月 1 日，业务代码只比较年份），无法识别的取值直接报错且不回显取值。文件范围：`AgentEvalV2.java`（接线）、`AgentJobSearchEvalV2Tests.java`（新增映射测试）、本文件。未修改任务、快照、profile、`AgentEvalV2Grading.java` 或判定语义，`ACCEPTANCE_DIGEST` 不变；未查看 acceptance profile 的取值，仅由测试断言其各字段映射成功。

- 2026-10-30，业务提交 `83d007fc150e210baa4a5009abfdd0663f6c33b0`（分支 `feat/agent-recommendation-evidence`）。原因：推荐工具新增当前简历依赖与必须经程序校验的简历引用，旧构造调用无法编译，dev 参考解缺少新增入参。v2 接线仅在 `AgentEvalV2.java` 给 `SubmitRecommendationsTool` 注入已有的 `ResumeService`；按协议更新 `tasks/dev.json` 中 d01、d06、d07、d08、d09 的参考提交，增加当前 resumeId 与来自既有 dev 简历的 quote/supports，不改任务要求或检查项。记录文件为本文件。acceptance 任务、快照、profile、`AgentEvalV2Grading.java` 和冻结摘要均未改动。只运行本地离线测试，未调用真实模型、未新增正式运行或报数。

- 2026-10-30，业务提交 `c4505218220d90a262cc4d8d3ef83053ef50faed`（分支 `feat/agent-verify-priority-freshness`）。原因：核实结果新增核实时间与时效判断，`VerifyBossJobTool`、`SubmitRecommendationsTool` 构造函数新增 `JdFreshness`（后者另需 `ConversationStore`），未带核实时间的 FULL 岗位按过期处理。接线只改 `AgentEvalV2.java`：注入用系统时钟和默认阈值构造的 `JdFreshness` 与已有的 `store`，回放生成 FULL 岗位时映射当次核实时间。文件范围：`AgentEvalV2.java`、本文件。dev 参考解按新规则仍全部通过，未改动；acceptance 任务、快照、profile、`AgentEvalV2Grading.java` 和冻结摘要均未改动。只运行本地离线测试，未调用真实模型、未新增正式运行或报数。

## 作废运行

2026-10-26 的 baseline 运行（结果文件 results/2026-10-26T09-48-06.488007300Z-baseline.json，原状态 completed）作废：运行中途模型服务商余额耗尽，18 次 trial 以 AI_BALANCE_INSUFFICIENT 结束并被计为 OTHER（failureCategories.OTHER = 18，与该错误码的 trial 数一致）。作废依据仅为错误码计数，未查看分数或任务详情。#112 起服务商故障会直接中止运行并记为 interrupted。baseline 槽位按未使用处理。

2026-10-26T09:43 的 baseline 运行（结果文件 results/2026-10-26T09-43-11.423624600Z-baseline.json，原状态 completed）同样作废：开始运行时余额已不足，54 次 trial 中 53 次计为 OTHER，AI_BALANCE_INSUFFICIENT 共出现 118 次。该行曾在整理 #113 时被误删，现依据本地备份补回。作废依据仅为错误码计数。

## 实际运行记录

| 时间 (UTC) | 运行 commit | 模型 | 槽位 | 状态 | 结果文件 |
| --- | --- | --- | --- | --- | --- |
| 2026-10-26T09:43:11.423624600Z | de758f52d1ed5ed80ebb71eb029ae18e78a5d359 | deepseek-flash | baseline | void | results/2026-10-26T09-43-11.423624600Z-baseline.json |
| 2026-10-26T09:48:06.488007300Z | de758f52d1ed5ed80ebb71eb029ae18e78a5d359 | deepseek-flash | baseline | void | results/2026-10-26T09-48-06.488007300Z-baseline.json |
| 2026-10-26T10:33:13.039737200Z | 4aa9eb58928a8dfeb83b6c1b5865d05151627988 | deepseek-flash | baseline | completed | results/2026-10-26T10-33-13.039737200Z-baseline.json |
| 2026-10-29T12:02:50.669374400Z | cb029c18f1c9b1d2bc03dc165d2329d0aa202255 | deepseek-flash | structured | completed | results/2026-10-29T12-02-50.669374400Z-structured.json |
