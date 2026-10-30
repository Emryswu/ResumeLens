# Agent 岗位搜索评测 v3 协议

v2 的两个正式槽位（baseline、structured）已经用完，见 `evals/agent-v2/RUN-LOG.md`。v3 是全新的一版：任务、快照、构造简历和城市方向都是重新写的，dev 为成都 Go 后端方向，acceptance 为深圳 Java 后端方向，与 v2 的杭州 AI 应用、广州前端不重复。回放和接线代码以 v2 为基础改写；评分代码在 v2 检查项的基础上新增了检查项和指标。出题时没有打开 v2 的 acceptance 任务、acceptance 快照和任何正式运行结果，只参考了 v2 的协议、README、dev 数据格式和评测代码。纪律沿用 v2 协议，并以 `experiments/holdout/PROTOCOL.md` 为参考。

## 和 v2 的区别

- **按生产环境接线。** v2 的回放中，排序工具是替身，循环没有接入在校生条件和 `VerifyPriority`，因此测不到 #118、#122 的行为。v3 按 `AgentConfig` 接线：在校生条件来自求职条件，`VerifyPriority` 已接入；注册的工具全部是生产实现，排序也使用生产的 `RankJobsForResumeTool`。只有存储、语义相似度和浏览器使用内存替身，相似度取自快照中的 `evalSimilarity`。
- **新增检查项：** 推荐理由的简历引用必须对口（#121），明显冲突的岗位不能占用核实名额（#122），核实已过期的 JD 必须重新核实（#122）。
- **新增运行指标：** 任务完成率、门槛误推荐率、每个推荐的工具调用数、被拒绝的冲突核实次数。

## 版本构成与冻结

- `tasks/dev.json` 和 `snapshots/dev/` 是 dev 集，可用于排障和调试。
- `tasks/acceptance.json` 和 `snapshots/acceptance/` 是 acceptance 集，合入即冻结。任务输入、原始快照（列表、详情、职位库预置岗位）、构造简历、金标规则和判定代码的语义共同构成 v3。
- 评分语义单独放在 `java/.../AgentEvalV3Grading.java`，包括各检查项、失败分类及其顺序、相似度阈值、关键词归一化和指标的计数口径。这个文件随 acceptance 一起冻结。回放与接线代码放在 `AgentEvalV3.java`，不属于评分语义。
- 摘要覆盖 `tasks/acceptance.json`、`snapshots/acceptance/` 下的全部文件和 `AgentEvalV3Grading.java`，计算时忽略换行符差异，结果写死在 `AgentJobSearchEvalV3Tests.ACCEPTANCE_DIGEST`。改动其中任何文件，CI 都会失败；正式运行入口也会校验摘要，不一致就拒绝运行。需要改变时建立 v4，不得为了通过检查去改常量。
- dev 与 acceptance 不共用任务，也不共用快照。每个岗位的 `sourceJobId` 全局唯一（包括职位库预置岗位），测试会检查这一点。
- v3 合入前已确认：在被测业务代码上，acceptance 参考解全部通过；冲突岗位确实被生产排序标为 `hardConflict`，预置岗位确实显示为 `EXPIRED`。

## 预先登记的一次正式运行

**current**：被测业务代码为 `31413632511d1805205e51d011dfe0e582122da9`，即创建 v3 时的 main（#123 合入后）。评测基础设施可以在其后只增加 eval 代码的提交上执行。运行入口会检查 `jd-rag-resume-backend/src/main` 与该 SHA 是否一致（包括未跟踪文件），不一致就拒绝运行。报告同时登记实际运行的 commit SHA 和被测业务 SHA。

预先规定：每个任务独立重复 3 次，合起来算一次运行。报告给出每个任务的通过次数和分母（x/3），三次重复不能当作三个独立任务。dev 和 acceptance 在同一次运行中执行，分组报告。

v3 只有这一个槽位。只要依据 acceptance 结果调整了 Agent、数据或配置，v3 即作废，继续正式报数要另建新版本。禁止中途查看 acceptance 输出后调参再补跑。失败、中断和误触都要写入 `RUN-LOG.md`，不能当作没有运行过。

## 冻结后唯一允许的接线修复

如果之后的业务代码改了构造函数、依赖或 DTO，导致评测 Java 接线代码编译失败，只允许修复接线：构造函数参数、依赖注入、mock 与 DTO 的字段映射。接线修复只能改 `AgentEvalV3.java`，不得改 `AgentEvalV3Grading.java`，也不得修改任务、快照、检查条件或判定语义，更不得依据 acceptance 成绩调整业务代码。每次修复都要在 `RUN-LOG.md` 登记日期、commit、原因和只涉及接线的文件范围。接线修复不会带来第二次报数资格。正式运行仍然只针对上面登记的业务 SHA。

dev 集不冻结。如果 dev 参考解因为业务的正当变化而失败，可以更新 dev 参考解，并在 `RUN-LOG.md` 登记日期、commit、原因和改动的任务。acceptance 和评分文件仍然不得改动。

## 数据与隔离

- 所有列表、详情和职位库岗位都是构造的测试数据，绝不访问 BOSS 或其他招聘服务。简历邮箱使用 `example.com`，手机号是虚构号码，公司名也是虚构的。简历里保留手机号，用来覆盖 #123 的外发打码和引用校验链路。
- 列表快照的格式就是浏览器插件的最终事件，由生产代码 `BrowserExtensionJobCollector` 校验后交给 `CollectBossJobsTool` 入库；详情快照由生产代码 `VerifyBossJobTool` 校验身份与正文后升级为 FULL。快照里的 `evalSimilarity` 只供语义相似度替身使用，插件协议里没有这个字段。
- 列表匹配规则：按任务 `searches` 的顺序，依次比较到达采集器的实际命令（继承之后的 city、experience、keyword）和可选的 `attempt`（第几次到达采集器）。关键词比较时忽略大小写和空白，并去掉结尾的「实习/实习生/岗位」。没有命中时回放 `generic-unrecorded.json`（`unrecordedCondition=true` 的通用不对口列表），trace 记录「未录制条件」，不会因为未知搜索词而报错。
- 核实没有详情快照的岗位时，以 `EVAL_SNAPSHOT_MISSING` 显式失败。
- 任务的 `preload` 列出由更早会话存进职位库的岗位：取自 `snapshots/<group>/library/`，深度为 FULL，核实时间为运行时刻往前 `verifiedDaysAgo` 天。重新核实时回放 `details/` 下的当前版本，当前版本可以与旧版不同。
- 每次 trial 都使用全新的职位库（含预置岗位）、页数额度、搜索上下文和 transcript。
- 模拟求职条件直接使用业务字段名（`educationLevel`、`currentlyStudent`、`expectedGraduationDate`、`workExperienceYears` 等）。
- 模拟用户对任何确认卡片一律拒绝，因为所有任务都没有要求放宽条件或发起分析。拒绝后本轮继续，trace 记录 `confirmation declined`。

## 评分

只依据 transcript 中成对的工具调用与结果，以及回放采集器实际收到的命令来判定，不采信模型的文字声明。

| 检查 | 通过条件 |
| --- | --- |
| `prerequisites` | 第一次调用 `collect_boss_jobs`（即使被拒绝）之前，已成功读取简历正文和求职条件 |
| `inherit_filters` | 首轮和后续轮都有到达采集器的搜索，并且每一次都带着指定的 city 和 experience |
| `stop_on_signal` | 出现指定停止信号（未登录 `BOSS_NOT_LOGGED_IN`；第一页就 `NO_MORE_RESULTS`）后，同一轮不再调用 `collect_boss_jobs`（被服务端拒绝的调用也算违规） |
| `no_listing_as_full` | 提交推荐的每个岗位都已经由 `verify_boss_job` 核实为 FULL（即使服务端拒绝了未核实的岗位，提交本身也算违规） |
| `submit_accepted` | 至少一个岗位经 `submit_recommendations` 验收通过；口头声称推荐不算 |
| `replan_offtarget` | 首次实际采集的结果全部不对口（相似度低于 0.5），先排序，再换关键词补搜成功，并且没有触发采集预算拒绝 |
| `gate_excluded` | 只在完整 JD 里写明的学历或经验硬门槛岗位，已核实完整 JD，并且没有出现在任何一次验收通过的推荐中（由服务端拒绝也算排除） |
| `preferred_accepted` | 学历或经验只写「优先」的岗位已核实完整 JD，并通过正式验收 |
| `resume_evidence_on_point` | 指定岗位通过正式验收，且它的简历引用中至少有一条包含任务列出的关键词之一（不区分大小写）。引用是否逐字摘自简历由业务代码校验；这一项只看引用的经历是否对得上岗位核心要求 |
| `conflict_skipped` | 列表数据已能判定硬冲突的岗位（`listing_tag`：BOSS 学历标签高于用户学历；`city`：不在本次求职城市），在本轮既没有被核实为 FULL，也没有通过验收。尝试核实被服务端以 `VERIFY_HARD_CONFLICT` 拒绝，不算违规，但计入指标 |
| `stale_reverified` | 预置的过期岗位在本轮重新核实为 FULL；`accept` 要求重新核实后仍然通过验收，`exclude` 要求它（当前版本已加了硬门槛）没有通过验收 |

门槛岗位、冲突岗位和过期岗位都按相似度排在前面，认真的 Agent 一定会考虑它们。

一个 trial 只有在每一轮都以 COMPLETED 结束、且所有检查都通过时才算通过。失败按以下顺序归入第一个符合的类别：

1. `SNAPSHOT_MISSING`：核实了没有快照的岗位。
2. `STEP_LIMIT`：步数用完。
3. `PLAN_PROTOCOL`：结束某一轮的错误码以 `AGENT_PLAN_` 开头。已经被纠正、没有结束这一轮的计划错误不算。
4. `OTHER`：超时、其他循环失败、环境或模型异常、测试运行异常。
5. `RULE_VIOLATION`：其余情况，包括检查未通过和 `AGENT_REPLAN_LIMIT`。

### 指标（按组汇总，口径在 `AgentEvalV3Grading.metrics`）

- **任务完成率**：通过的 trial 数 / trial 总数。
- **门槛误推荐率**：`gate_excluded`、`conflict_skipped` 和 `stale_reverified(exclude)` 这三类检查里，对应岗位仍然通过验收的次数 / 这类检查的总次数。
- **每个推荐的工具调用数**：在至少有一个推荐通过验收的 trial 里，实际执行的工具调用总数 / 通过验收的推荐总数。被服务端在执行前拒绝（`executed=false`）的调用不计入。
- **被拒绝的冲突核实次数**：`verify_boss_job` 被 `VERIFY_HARD_CONFLICT` 拒绝的总次数。

报告还汇总各类别的计数、每条规则的通过次数、每个任务的参考解是否仍能通过（`referencePassed`，离线计算），并保留失败 transcript 的摘要。

## 运行纪律

- CI 只运行离线检查：ScriptedAgentModel 跑通评测管线；dev 参考解通过；dev 的冲突与过期前提经生产排序成立；acceptance 参考解能完整回放（停止任务确实回放了一次浏览器）；检查项、指标与失败分类的自测；确认卡片被拒绝后本轮继续；数据完整性与冻结摘要。CI 永远不调用真实模型。
- 真实模型只有在显式设置 `RUN_AGENT_EVAL=true`，并提供 `AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL` 和运行槽位 `AGENT_EVAL_SLOT=current` 时才会运行。`RUN-LOG.md` 中已有 `| current | completed |` 时不得再次运行。
- 模型服务商故障（错误码 `AI_BALANCE_INSUFFICIENT`、`AI_AUTH_FAILED`、`AI_NOT_CONFIGURED`、`AI_RATE_LIMITED`、`AI_SERVICE_UNAVAILABLE`、`AI_REQUEST_ERROR`、`AI_REQUEST_INTERRUPTED`）与被测 Agent 无关：任何一次 trial 出现这类错误，运行立即中止并记为 `interrupted`，该 trial 不计入结果，报告写明 `abortReason`，槽位不被占用。超时（`AI_TIMEOUT`）和模型回复格式异常仍按 `OTHER` 计分。判断只依据错误码，不得参考任何分数。
- 正式运行等待人工通知。没有正式运行之前，日志里只有预登记计划。

## 已知不覆盖

- 放宽条件的确认卡片（#118 的 `relax_job_search_condition`）：模拟用户一律拒绝，不测"同意后放宽"这条路径。
- 浏览器任务被取消或超时：快照回放总是以 completed 结束。
- `search_resume_evidence`、`get_latest_analysis`、`start_analysis` 不在注册的工具里（依赖向量检索与分析流水线），模型调用会得到 `UNKNOWN_TOOL`。
