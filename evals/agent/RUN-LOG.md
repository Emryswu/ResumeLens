# Agent v1 运行登记

| 预登记槽位 | 被测版本 | 状态 | 正式运行结果 |
| --- | --- | --- | --- |
| baseline | 5fdaca01bf2c38a625b6bf98616a0c11bff6dde4 | 已完成 | results/2026-10-26T07-30-31.745006500Z-baseline.json（12/36） |
| structured | 「学历/经验结构化」PR 合入 main 后 | 作废（v1 已关闭，见下） | — |

后续每次真实调用追加日期（UTC）、运行 commit、模型、槽位、状态和结果文件路径。失败、中断、误触也要记录。

## v1 关闭（2026-10-26）

baseline 正式运行后，查看了其失败 transcript（含 acceptance/v1 任务），据此在 #109（`5b3126b`）修改了 Agent 计划执行器：并行调用多个计划步骤、计划与工具同发、计划格式无效时不再直接结束整轮，改为可纠正。按 PROTOCOL.md「基于 acceptance 结果调整了 Agent，v1 即作废」，v1 自此关闭：structured 槽位不再运行，`AgentJobSearchEvalTests` 拒绝任何新的 v1 正式运行。baseline 结果保留为历史记录，不与之后的版本直接比较。后续正式报数使用 v2（全新 acceptance 任务，出题方不读取 `evals/agent/`），v2 预登记两次运行：#109 之后的 main，以及「学历/经验结构化」PR 合入后。

## 实际运行记录

| 日期 UTC | 运行 commit | 模型 ID | 槽位 | 状态 | 结果文件 |
| --- | --- | --- | --- | --- | --- |
| 2026-10-25T14:26:08Z | 473de1e8307e5f95c02d0638ae4f520c19121ff2 | deepseek-flash | baseline | interrupted | 未生成结果文件。OTHER：唯一一次 Maven 调用后进程已退出，仅留下 target/classes 中的资源文件，无 .class、Surefire 报告或评测产物；原输出会话已失效，具体错误与退出码无法恢复。此时间为事后登记时间；业务基线 5fdaca01bf2c38a625b6bf98616a0c11bff6dde4；未修改代码、任务、快照或配置，未修复、未重跑；各任务无可验证 trial 成绩。 |
| 2026-10-26T07:30:31.745006500Z | 44d74eaa14387a89df22c5cc4221420862180afd | deepseek-flash | baseline | completed | results/2026-10-26T07-30-31.745006500Z-baseline.json |
