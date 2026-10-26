# Agent 岗位搜索评测 v2 协议

v1 已关闭（见 `evals/agent/RUN-LOG.md`「v1 关闭」）。v2 是全新的一版，任务、快照、构造简历与判定代码都重新编写。出题时没有读取 v1 的任务、快照、结果，也没有读取学历/经验结构化 PR 的代码。纪律沿用 v1 协议，并以 `experiments/holdout/PROTOCOL.md` 为参考。

## 版本构成与冻结
- `tasks/dev.json` 和 `snapshots/dev/` 是 dev 集，可用于排障和调试。
- `tasks/acceptance.json` 和 `snapshots/acceptance/` 是 acceptance 集。合入即冻结，任务输入、原始快照、构造简历、金标规则和判定代码的语义共同构成 v2。
- acceptance 输入的 SHA-256 摘要写死在 `AgentJobSearchEvalV2Tests.ACCEPTANCE_DIGEST`。改动任何 acceptance 文件，CI 就会失败；正式运行入口也会校验摘要，不一致则拒绝运行。需要改变时建立 v3，不得为了通过检查去改常量。
- dev 与 acceptance 不共用任务，也不共用快照。每个岗位的 `sourceJobId` 全局唯一，测试会检查这一点。

## 预先登记的两次正式运行
1. **baseline**：被测业务代码为 `c1c80c5ace3314de34949fd74e061dea8bb20990`，即创建 v2 时的 main（#109 之后、#110 关闭 v1）。评测基础设施可以在其后只增加 eval 代码的提交上执行。运行入口会检查 `jd-rag-resume-backend/src/main` 与该 SHA 是否一致（包括未跟踪文件），不一致则拒绝运行。报告同时登记实际运行的 commit SHA 和被测业务 SHA。
2. **structured**：独立的「学历/经验结构化」PR（把 JD 中的学历、经验要求区分为 required / preferred）合入 main 之后运行，被测业务 SHA 即运行时的 commit。该 PR 不得读取 `evals/agent-v2/`，包括任务、快照、结果和判定规则。

两次正式运行都预先规定：每个任务独立重复 3 次，合起来算一次运行。报告给出每个任务的通过次数和分母（x/3），三次重复不能当作三个独立任务。dev 和 acceptance 在同一次运行中执行，分组报告。

除这两次运行外，只要依据 acceptance 结果调整了 Agent、数据或配置，v2 即作废，继续正式报数要另建新版本。禁止中途查看 acceptance 输出后调参再补跑。失败、中断和误触也要写入 `RUN-LOG.md`，不能当作没有运行过。

## 冻结后唯一允许的接线修复
结构化 PR 如果改了构造函数、依赖或 DTO，导致评测 Java 接线代码编译失败，可以由**非结构化 PR 的一方**只修复接线：构造函数参数、依赖注入、mock 与 DTO 的字段映射（包括把 `profile.json` 中已有的学历、在校状态、毕业年月、工作年数映射到新字段名）。不得修改任务、快照、检查条件或判定语义，也不得依据 acceptance 成绩调整业务代码。每次修复都要在 `RUN-LOG.md` 登记日期、commit、原因和只涉及接线的文件范围。接线修复不会重置正式运行次数，也不会带来第三次报数资格。

## 数据与隔离
- 所有列表和详情都是构造的测试数据，绝不访问 BOSS 或其他招聘服务。简历邮箱使用 `example.com`，公司名为虚构。
- 列表快照的格式就是浏览器插件的最终事件，由生产代码 `BrowserExtensionJobCollector` 校验后交给 `CollectBossJobsTool` 入库；详情快照由生产代码 `VerifyBossJobTool` 校验身份与正文后升级为 FULL。快照里的 `evalSimilarity` 只供替身排序工具使用，插件协议里没有这个字段。
- 列表匹配规则：按任务 `searches` 的顺序，依次比较到达采集器的实际命令（继承之后的 city、experience、keyword）与可选的 `attempt`（第几次到达采集器）。关键词比较时忽略大小写和空白，并去掉结尾的「实习/实习生/岗位」。没有命中时回放 `generic-unrecorded.json`（`unrecordedCondition=true` 的通用不对口列表），trace 记录「未录制条件」，不会因为未知搜索词而报错。
- 核实没有详情快照的岗位时，以 `EVAL_SNAPSHOT_MISSING` 显式失败。
- 每次 trial 都使用全新的职位库、页数额度、搜索上下文和 transcript。
- 模拟求职条件固定包含学历、在校状态、预计毕业年月和工作年数（`education`、`studentStatus`、`expectedGraduation`、`workExperienceYears`）。它们经被测业务代码的 `JobSearchPreferencesView` 反序列化：业务代码不认识的字段被忽略，因此 baseline 下模型只能从简历正文得知学历与经历，结构化 PR 之后由业务代码决定是否提供。不得为某次运行回写 profile。

## 评分
只依据 transcript 中成对的工具调用与结果，以及回放采集器实际收到的命令来判定，不采信模型的文字声明。检查项如下：

| 检查 | 通过条件 |
| --- | --- |
| `prerequisites` | 第一次调用 `collect_boss_jobs`（即使被拒绝）之前，已成功读取简历正文和求职条件 |
| `inherit_filters` | 首轮和后续轮都有到达采集器的搜索，并且每一次都带着指定的 city 和 experience |
| `stop_on_signal` | 出现指定停止信号（未登录 `BOSS_NOT_LOGGED_IN`；第一页就 `NO_MORE_RESULTS`）后，同一轮不再调用 `collect_boss_jobs`（被服务端拒绝的调用也算违规） |
| `no_listing_as_full` | 提交推荐的每个岗位都已经由 `verify_boss_job` 核实为 FULL（即使服务端拒绝了未核实的岗位，提交本身也算违规） |
| `submit_accepted` | 至少一个岗位经 `submit_recommendations` 验收通过；口头声称推荐不算 |
| `replan_offtarget` | 首次实际采集的结果全部不对口（相似度低于 0.5），先排序，再换关键词补搜成功，并且没有触发采集预算拒绝 |
| `gate_excluded` | 学历或经验硬门槛岗位已核实完整 JD，且没有出现在任何一次验收通过的推荐中（由服务端拒绝也算排除） |
| `preferred_accepted` | 学历或经验只写「优先」的岗位已核实完整 JD，并通过正式验收 |

门槛岗位在排序中排第一，按系统规则会被优先核实；没有核实就无法证明是依据门槛排除，所以记为失败。

一个 trial 只有在每一轮都以 COMPLETED 结束、且所有检查都通过时才算通过。失败按以下顺序归入第一个符合的类别：

1. `SNAPSHOT_MISSING`：核实了没有快照的岗位。
2. `STEP_LIMIT`：步数用完。
3. `PLAN_PROTOCOL`：结束某一轮的错误码以 `AGENT_PLAN_` 开头（例如 `AGENT_PLAN_INVALID`、`AGENT_PLAN_MISMATCH`、`AGENT_PLAN_INCOMPLETE`）。已经被纠正、没有结束这一轮的计划错误不算。
4. `OTHER`：超时、其他循环失败、环境或模型异常、测试运行异常。
5. `RULE_VIOLATION`：其余情况，包括检查未通过和 `AGENT_REPLAN_LIMIT`。

报告汇总各类别的计数、每条规则的通过次数，以及每个任务的参考解是否仍能通过（`referencePassed`，离线计算），并保留失败 transcript 的摘要。

## 运行纪律
- CI 只运行离线检查：ScriptedAgentModel 跑通评测管线；dev 参考解通过；acceptance 参考解能完整回放；检查项与失败分类的自测；数据完整性与冻结摘要。CI 永远不调用真实模型。
- acceptance 参考解是否通过取决于被测业务代码，所以 CI 不对它做断言，只在正式运行时记录。v2 合入前，已在 baseline 业务代码上确认 acceptance 参考解全部通过。
- 真实模型只有在显式设置 `RUN_AGENT_EVAL=true`，并提供 `AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL` 和运行槽位 `AGENT_EVAL_SLOT` 时才会运行。槽位只能是 `baseline` 或 `structured`；`RUN-LOG.md` 中已有 `| <slot> | completed |` 的槽位不得再次运行。
- 首次 baseline 运行等待人工通知。没有正式运行之前，日志里只有两条预登记计划。
