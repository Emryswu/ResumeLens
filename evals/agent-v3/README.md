# AI 助手岗位搜索评测 v3

v3 接替两个正式槽位都已用完的 v2（`evals/agent-v2/`），纪律见 [`PROTOCOL.md`](PROTOCOL.md)。这里的简历、公司和岗位都是构造的评测数据，不是真实的 BOSS 岗位。

## 目录

| 路径 | 内容 |
| --- | --- |
| `tasks/dev.json` | dev 任务，可调试 |
| `tasks/acceptance.json` | acceptance 任务，冻结 |
| `snapshots/<group>/profile.json` | 构造简历与求职条件（dev 为成都 Go 后端方向，acceptance 为深圳 Java 后端方向，都是 2027 届本科在校生） |
| `snapshots/<group>/listings/` | 插件最终事件格式的列表快照，含 `generic-unrecorded.json` 和未登录快照 |
| `snapshots/<group>/details/` | 按 `sourceJobId` 命名的完整 JD 快照（过期岗位放的是当前版本） |
| `snapshots/<group>/library/` | 更早会话存进职位库的岗位：旧版完整 JD 与 `verifiedDaysAgo` |
| `java/.../AgentEvalV3Grading.java` | 评分语义：检查项、失败分类、相似度阈值、指标口径；随 acceptance 冻结 |
| `java/.../AgentEvalV3.java` | 回放、按生产环境接线的工具与参考解模型；接线修复只改这个文件 |
| `java/.../AgentJobSearchEvalV3Tests.java` | 离线检查与真实运行入口 |
| `RUN-LOG.md` | 预登记与运行记录 |
| `results/` | 正式运行时生成的报告 |

## 任务

| 规则 | dev | acceptance |
| --- | --- | --- |
| 采集前先读简历和求职条件 | v3d01、v3d05 | v3a01、v3a05 |
| 多轮对话继承城市与经验筛选 | v3d02 | v3a02 |
| 未登录时停止，不再补搜 | v3d03 | v3a03 |
| 第一页就无更多结果时停止 | v3d04 | v3a04 |
| 列表摘要不能当完整 JD | v3d01、v3d06–v3d09 | v3a01、v3a06–v3a09 |
| 必须经 submit_recommendations 验收 | v3d01、v3d06、v3d08、v3d10 | v3a01、v3a06、v3a08、v3a10 |
| 首轮不对口时换关键词补搜 | v3d05 | v3a05 |
| 学历硬门槛排除（只写在完整 JD 里） | v3d06 | v3a06 |
| 学历「优先」不排除 | v3d07 | v3a07 |
| 经验年限硬门槛排除（只写在完整 JD 里） | v3d08 | v3a08 |
| 经验「优先」不排除 | v3d09 | v3a09 |
| 推荐引用的简历经历对口 | v3d01、v3d10 | v3a01、v3a10 |
| 列表标签已判定冲突的岗位不占核实名额 | v3d10 | v3a10 |
| 不在求职城市的岗位不占核实名额 | v3d10 | v3a10 |
| 过期 JD 重新核实后仍合格，正常推荐 | v3d11 | v3a11 |
| 过期 JD 重新核实后已加门槛，不推荐 | v3d11 | v3a11 |

每个任务还带有一份参考解（`reference`），按轮列出工具调用，可以使用 `$resume`、`$job:<sourceJobId>`、`$lastCollect` 占位。离线检查会用它把任务完整跑一遍，证明任务可解，并证明检查项会接受正确的运行。

## 指标

报告的 `metrics` 按组给出：任务完成率、门槛误推荐率、每个推荐的工具调用数、被拒绝的冲突核实次数。计数口径见协议。

## 运行方式

评测代码由 `jd-rag-resume-backend/pom.xml` 的 build-helper 在测试阶段编译。它运行真实的 `AgentLoop`，并按 `AgentConfig` 接入在校生条件与 `VerifyPriority`；注册的工具全部是生产实现，包括排序。只有存储、语义相似度（取自快照的 `evalSimilarity`）和浏览器使用内存回放。

离线检查（CI 执行，也包含在 `./mvnw -B --no-transfer-progress verify` 中）：

```bash
cd jd-rag-resume-backend && ./mvnw -B --no-transfer-progress -Dtest=AgentJobSearchEvalV3Tests test
```

ScriptedAgentModel 是演示用的脚本模型，只认识几个固定的简历方向，可能直接回答而不搜索，所以很多任务会失败。不要把脚本模型的通过率当作真实模型的基线。

正式运行只在收到通知后进行：在有模型 Key 的机器上，切到业务代码与登记 SHA 一致的提交，设置 `RUN_AGENT_EVAL=true`、`AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL` 和 `AGENT_EVAL_SLOT=current`，然后执行上面同一条命令。Key 不会写入结果。报告写入 `results/<时间>-current.json`，并在 `RUN-LOG.md` 追加一行。
