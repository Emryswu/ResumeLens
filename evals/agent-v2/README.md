# AI 助手岗位搜索评测 v2

v2 替代已关闭的 v1（`evals/agent/`），纪律见 [`PROTOCOL.md`](PROTOCOL.md)。这里的简历、公司和岗位都是构造的评测数据，不是真实的 BOSS 岗位。

## 目录

| 路径 | 内容 |
| --- | --- |
| `tasks/dev.json` | dev 任务，可调试 |
| `tasks/acceptance.json` | acceptance 任务，冻结 |
| `snapshots/<group>/profile.json` | 构造简历与求职条件（dev 为杭州 AI 应用开发方向，acceptance 为广州前端方向） |
| `snapshots/<group>/listings/` | 插件最终事件格式的列表快照，含 `generic-unrecorded.json` 和未登录快照 |
| `snapshots/<group>/details/` | 按 `sourceJobId` 命名的完整 JD 快照 |
| `java/.../AgentEvalV2Grading.java` | 评分语义：检查项、失败分类、相似度阈值；随 acceptance 冻结 |
| `java/.../AgentEvalV2.java` | 回放、工具接线与参考解模型；接线修复只改这个文件 |
| `java/.../AgentJobSearchEvalV2Tests.java` | 离线检查与真实运行入口 |
| `RUN-LOG.md` | 预登记与运行记录 |
| `results/` | 正式运行时生成的报告 |

## 任务

| 规则 | dev | acceptance |
| --- | --- | --- |
| 采集前先读简历和求职条件 | d01、d05 | a01、a05 |
| 多轮对话继承城市与经验筛选 | d02 | a02 |
| 未登录时停止，不再补搜 | d03 | a03 |
| 第一页就无更多结果时停止 | d04 | a04 |
| 列表摘要不能当完整 JD | d01、d06–d09 | a01、a06–a09 |
| 必须经 submit_recommendations 验收 | d01、d06、d08 | a01、a06、a08 |
| 首轮不对口时换关键词补搜 | d05 | a05 |
| 学历硬门槛排除 | d06 | a06 |
| 学历「优先」不排除 | d07 | a07 |
| 经验年限硬门槛排除 | d08 | a08 |
| 经验「优先」不排除 | d09 | a09 |

每个任务还带有一份参考解（`reference`），按轮列出工具调用，可以使用 `$resume`、`$job:<sourceJobId>`、`$lastCollect` 占位。离线检查会用它把任务完整跑一遍，证明任务可解，并证明检查项会接受正确的运行。

## 运行方式

评测代码由 `jd-rag-resume-backend/pom.xml` 的 build-helper 在测试阶段编译。它运行真实的 `AgentLoop`，以及简历、求职条件、采集、核实、读 JD、条件检查和推荐验收这些生产工具；只把存储和浏览器换成内存中的回放。排序是唯一的替身工具，按快照的 `evalSimilarity` 排序。注册的工具里没有 `search_resume_evidence`、`get_latest_analysis` 和 `start_analysis`，模型调用它们会得到 `UNKNOWN_TOOL`。

离线检查（CI 执行，也包含在 `./mvnw -B --no-transfer-progress verify` 中）：

```bash
cd jd-rag-resume-backend && ./mvnw -B --no-transfer-progress -Dtest=AgentJobSearchEvalV2Tests test
```

ScriptedAgentModel 只用来检查编排、回放和判定的运行路径，它不核实、不提交推荐，所以很多任务会失败。不要把脚本模型的通过率当作真实模型的基线。

正式运行只在收到通知后进行，在有模型 Key 的机器上设置 `RUN_AGENT_EVAL=true`、`AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL` 和 `AGENT_EVAL_SLOT=baseline`（或 `structured`），然后执行上面同一条命令。Key 不会写入结果。报告写入 `results/<时间>-<slot>.json`，并在 `RUN-LOG.md` 追加一行。
