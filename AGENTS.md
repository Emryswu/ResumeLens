# AGENTS.md

给接手本仓库的 AI 编码助手（Claude Code、Codex 等）看的约定。命令以 `.github/workflows/ci.yml` 和各 `package.json` 为准；两边不一致时，以它们为准并顺手修正本文件。

## 仓库结构

| 目录 | 内容 |
| --- | --- |
| `jd-rag-resume-backend/` | 后端：Spring Boot 3.5、Java 21、Maven（自带 `mvnw`） |
| `jd-rag-resume-frontend/` | 前端：Next.js 16，由 vinext 构建运行；React 19、TypeScript；Node `>=22.13.0` |
| `browser-extension/` | Chrome 扩展，无第三方依赖 |
| `experiments/` | 检索实验：`threshold-sweep`（阈值扫描，dev 集）、`rag-ablation`（全文 vs RAG 消融）、`holdout`（独立测试集） |

## 命令

CI（`ci.yml`）对每个指向 main 的 PR 跑以下全部检查。

**后端**（在 `jd-rag-resume-backend/` 下）

- 构建与测试：`./mvnw -B --no-transfer-progress verify`（Windows 用 `mvnw.cmd`）
- 没有单独的 lint 步骤。
- Testcontainers 用例需要 Docker，本机没有 docker 命令时会被跳过，只在 CI 上真正执行。
- 改动 RAG 相关代码（`rag/` 包、`pom.xml`、`application.properties`）时，PR 还会触发 `embedding-regression.yml`，用真实 ONNX 模型跑 `RealEmbeddingRegressionTests`。

**前端**（在 `jd-rag-resume-frontend/` 下）

- 安装：`npm ci`
- lint：`npm run lint`
- 测试：`npm test`，包含 `npm run build` 生产构建和构建后的渲染测试。
- 测试文件在 `package.json` 的 `test` 脚本里逐个列出，新增测试文件必须加进去，否则不会运行。
- 本地开发：`npm run dev`

**浏览器扩展**（在 `browser-extension/` 下）

- 测试：`npm test`（`node --check` 各脚本 + `node --test tests/*.test.cjs`）
- 打包：`npm run pack`，产物为 `dist/resumelens-browser-extension-v*.zip`
- 没有依赖要装，也没有 lint 步骤。

**提交信息检查**（在仓库根目录下）

- 测试：`node --test .github/scripts/check-commit-messages.test.mjs`
- 自查本分支的提交：`node .github/scripts/check-commit-messages.mjs origin/main HEAD`

## 提交与 PR

- commit 和 PR 只署作者本人。不加 `Co-Authored-By`、会话链接（`claude.ai/code/session…`）、`Generated with ...` 之类的署名行。CI 的「提交信息署名检查」会拦下带这些字样的提交。
- PR 描述用中文。
- 有界面改动时，截图放在 `pr-assets/<prN>` 分支（如 `pr-assets/pr5`），不合入 main。PR 描述里用「改动前 / 改动后」两列表格嵌图。
- 改完必须先在本地跑通所改部分的 lint 和测试（见上方命令），全部通过再提交。

## 修 bug 与 review

- 修 bug 必须补回归测试：修复前测试失败，修复后通过。
- review 发现问题时，不只修这一处，还要防止同类问题再出现。优先改代码结构，让错误写不出来；其次补测试或 CI 检查，让错误被自动发现。两者都拦不住时，才写进文档。

## Agent 工具

- 会打开 BOSS 页面的工具（采集、核实详情等）必须加进 `JobSearchWorkflow.BOSS_TOOLS`，统一走一个预算入口（每轮采集次数、页数、核实次数、插件每日页数）。类名请带 `Boss`，`JobSearchWorkflowTests.everyBossToolInTheRegistryIsGovernedByTheBudget` 会扫描并拦下漏登记的工具。
- 职位的 `location` 由插件按 `城市·区·商圈` 拼接（见 `browser-extension/src/boss-api-extractor.js`），老数据可能是 `深圳市龙岗区` 这种纯文本。比对地点时去掉分隔符再比，测试要用真实格式的样本。
- AgentLoop 里新增的拒绝码（工具还没执行就拒绝的情况）必须走统一出口 `rejectBeforeExecution`，不得直接返回 `ToolEnvelope.error`：只有带 `executed=false` 的结果才不计入找岗预算、允许重试。工具自己在打开浏览器前拒绝时抛 `ToolNotStartedException`。工具执行前的校验（前置条件、会话条件、重复搜索、预算等）必须放在 `beginDeferred` 里执行前那段 try 中，异常会自动按未执行处理；和 `tool.begin(...)` 同一个 try 的只能是工具本身的执行。`UnexecutedCallBudgetTests` 会扫描 AgentLoop 源码拦下绕过出口的写法。
- 浏览器插件里每次打开 BOSS 页面都要先扣每日页数；连续打开详情页之间要有随机间隔，不能一口气秒开多个页面。
- 跨进程比较日期必须使用同一时区（BOSS 每日额度统一为 `Asia/Shanghai`），后端是日期的唯一来源，插件和采集服务按该时区校验后端下发的 `budgetDate`。

## 前端约定

- 跨页面要保留的状态不得只放组件 state。
- Agent transcript 以服务端为准，不接收客户端提交的历史。

- 前端禁止直接使用浏览器原生 select 等表单控件样式，统一使用项目自定义组件；选择菜单复用 `ChoiceMenu`，保持主题样式与键盘交互一致。
- 样式沿用 `app/workspace.css` 里的变量，图标用 `lucide-react`，不另起一套风格。`tests/icon-glyphs.test.ts` 禁止用 Unicode 字符充当图标。
- 图片统一通过 `app/components/BrandArt.tsx` 的 `BrandImage` 输出。`tests/brand-assets.test.ts` 会检查其他地方不能直接写 `<img>`。

## 编辑方式

- 大文件用局部编辑，不要整文件重写。

## 实验

- 改动 `experiments/holdout` 相关内容之前，先读 `experiments/holdout/PROTOCOL.md`。每个 holdout 版本只有一次用于报数的正式运行；根据它的结果调过参数，这份 holdout 就不能再当最终测试集。
