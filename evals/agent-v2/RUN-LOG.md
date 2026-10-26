# Agent v2 运行登记

## 预登记计划

| 槽位 | 被测业务代码 | 规模 | 状态 |
| --- | --- | --- | --- |
| baseline | `c1c80c5ace3314de34949fd74e061dea8bb20990`（创建 v2 时的 main） | dev 9 题 + acceptance 9 题，每题独立重复 3 次 | 未运行，等待人工通知 |
| structured | 「学历/经验结构化」PR 合入后的 main | 同上 | 未运行，等待该 PR 合入 |

## 冻结前检查（不计入正式运行）

2026-10-26：在 baseline 业务代码上，用离线参考解跑通全部 dev 与 acceptance 任务（均 PASS），确认任务可解；未调用真实模型。随后计算 acceptance 摘要 `b297c66f994bc1f8be6640a6ce44a737fa4a34b48549c0d3be37ee03d03ee9af`，写入 `AgentJobSearchEvalV2Tests.ACCEPTANCE_DIGEST`。

## 接线修复记录

暂无。

## 实际运行记录

| 时间 (UTC) | 运行 commit | 模型 | 槽位 | 状态 | 结果文件 |
| --- | --- | --- | --- | --- | --- |
