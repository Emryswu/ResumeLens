# Agent v1 运行登记

| 预登记槽位 | 被测版本 | 状态 | 正式运行结果 |
| --- | --- | --- | --- |
| baseline | 5fdaca01bf2c38a625b6bf98616a0c11bff6dde4 | 待运行（等通知） | — |
| structured | 「学历/经验结构化」PR 合入 main 后 | 待运行 | — |

后续每次真实调用追加日期（UTC）、运行 commit、模型、槽位、状态和结果文件路径。失败、中断、误触也要记录。

## 实际运行记录

| 日期 UTC | 运行 commit | 模型 ID | 槽位 | 状态 | 结果文件 |
| --- | --- | --- | --- | --- | --- |

| 2026-10-25T14:26:08Z | 473de1e8307e5f95c02d0638ae4f520c19121ff2 | deepseek-flash | baseline | interrupted | 未生成结果文件。OTHER：唯一一次 Maven 调用后进程已退出，仅留下 target/classes 中的资源文件，无 .class、Surefire 报告或评测产物；原输出会话已失效，具体错误与退出码无法恢复。此时间为事后登记时间；业务基线 5fdaca01bf2c38a625b6bf98616a0c11bff6dde4；未修改代码、任务、快照或配置，未修复、未重跑；各任务无可验证 trial 成绩。 |
