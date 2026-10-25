# AI 助手岗位搜索评测

此目录存放任务、脱敏构造的浏览器最终事件快照、冻结协议与结果。Java 评测运行器位于 `evals/agent/java/com/arthur/jdragresume/agent/AgentJobSearchEvalTests.java`，由 Maven build-helper 仅在测试阶段编译，运行真实 `AgentLoop`、`AgentModel` 与 `SubmitRecommendationsTool`；所有采集和详情页仅从本目录回放，不打开 Chrome/Patchright/BOSS，也不请求其他外部招聘服务。

`tasks/dev.json` 可以排障；`tasks/acceptance/v1.json` 和其引用的快照、判定语义按 `PROTOCOL.md` 冻结。每次 trial 重新创建 Job/Conversation 状态；已录制列表按关键词、深圳、在校生匹配，其他组合返回 `generic-unrecorded.json`，trace 记录「未录制条件」；核实未录制 jobId 显式返回 `EVAL_SNAPSHOT_MISSING`。这里的职位/简历均为评测样本，不声称是真实 BOSS 岗位。

离线 CI 自动执行 `cd jd-rag-resume-backend && ./mvnw -B --no-transfer-progress -Dtest=AgentJobSearchEvalTests test`。离线模式使用原有 `ScriptedAgentModel` 来检查编排、回放与规则判定的运行路径，某些业务任务可能失败，不把脚本模型的表现误当真实 DeepSeek 基线。

真实运行只在你明确通知时，在有 DeepSeek Key 的机器上设置以下环境变量：`RUN_AGENT_EVAL=true`、`AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL` 和 `AGENT_EVAL_SLOT=baseline`（或 `structured`），然后运行同一 Maven 测试入口。Key 不会写入结果。每任务固定独立跑三遍，结果放 `results/`，失败汇总类别为 `STEP_LIMIT`、`RULE_VIOLATION`、`SNAPSHOT_MISSING`、`OTHER`；`RUN-LOG.md` 追加登记。基线 slot 预登记被测业务版本为 `5fdaca0`，另外单独记录运行器所在的真实 commit SHA。

注意：基线运行要在结构化字段 PR 合入前进行；学历/经验结构化 PR 不得读这个目录。acceptance v1 只能用于预先登记的这两次正式运行，其他依据结果的迭代必须另建 v2。
