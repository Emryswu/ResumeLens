# Agent v2 运行登记

## 预登记计划

| 槽位 | 被测业务代码 | 规模 | 状态 |
| --- | --- | --- | --- |
| baseline | `c1c80c5ace3314de34949fd74e061dea8bb20990`（创建 v2 时的 main） | dev 9 题 + acceptance 9 题，每题独立重复 3 次 | 未运行，等待人工通知 |
| structured | 「学历/经验结构化」PR 合入后的 main | 同上 | 未运行，等待该 PR 合入 |

## 冻结前检查（不计入正式运行）

2026-10-26：在 baseline 业务代码上，用离线参考解跑通全部 dev 与 acceptance 任务（均 PASS），确认任务可解；未调用真实模型。#111 review 后，评分逻辑拆到 `AgentEvalV2Grading.java` 并纳入摘要，拆分后重新确认 acceptance 参考解均 PASS。摘要覆盖 `tasks/acceptance.json`、`snapshots/acceptance/` 和 `java/com/arthur/jdragresume/agent/AgentEvalV2Grading.java`，值为 `0b11fd025bc58d6bd130af5883419e020705c70f627915908cc9020cd7e9d351`，已写入 `AgentJobSearchEvalV2Tests.ACCEPTANCE_DIGEST`。

## 接线修复与 dev 参考解更新记录

暂无。

## 实际运行记录

| 时间 (UTC) | 运行 commit | 模型 | 槽位 | 状态 | 结果文件 |
| --- | --- | --- | --- | --- | --- |
