# Agent 岗位搜索评测 v1 协议

以 `experiments/holdout/PROTOCOL.md` 为纪律参考。dev 可调试，acceptance/v1 冻结；任务输入、原始快照、金标规则和判定代码的语义共同构成版本。acceptance/v1 一旦合入，不得根据结果修改其中任何内容；需要改变时建立 v2。

## 预先登记的仅有两次 v1 正式运行
1. **baseline**：基线业务代码为 `5fdaca01bf2c38a625b6bf98616a0c11bff6dde4`。评测基础设施允许以较新的只增加 eval 代码的提交执行，但报告必须同时登记实际运行 commit SHA 与被测业务基线 SHA。
2. **structured**：独立「学历/经验结构化」PR 合入 main 后运行。该 PR 不得读取 `evals/agent/`（包括任务、快照、结果与判定规则）。

两次正式运行均预先规定每任务独立重复 3 次，算作一次运行。禁止中途查看 acceptance 输出并调参与补跑。除这两次预先登记的正式运行外，只要基于 acceptance 结果调整了 Agent、数据或配置，v1 即作废；继续正式报数应制作全新版本。失败、中断和误触也写入日志，不能当作没有运行过。每次结果都应同时报告每任务通过次数和分母，不把三次重复误作三个独立任务。

## 数据与隔离
列表和详情由冻结的脱敏/构造快照回放，明确是测试数据，绝不访问 BOSS。列表条件未录制时固定回放标记 `unrecordedCondition=true` 的通用不对口快照，trace 写 `未录制条件`，不会因为未知搜索词而抛错。核实未录制的 jobId 必须以 `EVAL_SNAPSHOT_MISSING` 显式失败。每次 trial 使用全新职位状态、搜索上下文和 transcript。模拟求职条件自 v1 起固定包含学历、在校状态、预计毕业年月和工作年数；5fdaca0 基线忽略新增结构化字段，后续业务 PR 生效，不得回写 v1。

## 评分与报告
只按 transcript 中成对的工具调用与结果判定先读简历/条件、筛选继承、停止补搜、LISTING/FULL、submit_recommendations 实际验收和不对口补搜。需要正式推荐的任务，即使模型口头声称推荐成功而没有通过 submit 验收，也记失败。每次失败归一到 `STEP_LIMIT`（步数用完）、`RULE_VIOLATION`（违反规则）、`SNAPSHOT_MISSING`（核实缺少快照）或 `OTHER`（环境、模型、测试运行异常）。报告汇总各类别计数，并保留失败 transcript 摘要。

## 运行纪律
CI 仅运行 ScriptedAgentModel 离线框架检查。真实 DeepSeek 必须显式 `RUN_AGENT_EVAL=true`，设置 API Key、Base URL、模型 ID 和运行槽位，永不由 CI 调用。首次基线等待人工通知；无正式运行时日志只有两条预登记计划。
