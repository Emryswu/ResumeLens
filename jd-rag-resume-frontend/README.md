# jd-rag-resume-frontend · ResumeLens

简历 / JD 智能匹配前端工作台，使用 Next.js 16（vinext）、React 19 和 TypeScript。浅色水墨山水与深绿配色贯穿岗位匹配、简历与职位管理、结构化 RAG 报告和 AI 求职助手。

完整项目说明见上级目录：

→ [../README.md](../README.md)

## 页面与交互

| 入口 | 说明 |
| --- | --- |
| `/#workflow` | 岗位列表与匹配报告并排；语义粗排、Top N 精析、Markdown / 打印 PDF 导出 |
| `/#resumes`、`/#jobs` | 简历与职位管理，上传简历、JSON 批量导入 JD |
| `/#saved` | 按用户保存在当前浏览器的岗位收藏，仅存岗位 ID，不跨设备同步 |
| `/#history`、`/?analysisId=<id>` | 分析记录与指定报告入口 |
| `/resumes/[id]`、`/jobs/[id]` | 独立详情页，查看 / 编辑 / 删除 |
| `/assistant` | Tool-calling 对话、SSE 工具步骤、写操作确认卡与分析结果入口 |

报告分为「匹配依据 / 修改简历 / 岗位要求」。结构化优势和缺口带类别、说明和片段引用；点击片段打开原文抽屉，高亮仅用于定位词面重叠，检索参数与被过滤片段收在「检索详情」中。「修改简历」读取当前原文作为手动编辑草稿，可另存新简历或下载 TXT，尚不自动生成改写。

AI 助手处理中实时显示工具步骤，回答后收成摘要，点击打开完整过程侧栏。原文抽屉与过程侧栏复用 `SideDrawer`，窄屏从底部弹出，支持 Esc 关闭和焦点返回。已批准的写操作结果未知，或已执行但没有收到完整对话记录时，页面阻止沿用当前对话继续发送 / 重试，并提供分析记录或报告入口。

## 开发

需要 Node.js `>=22.13.0`。

```powershell
npm ci
# 后端默认 http://127.0.0.1:8080
$env:BACKEND_API_URL = "http://127.0.0.1:8080"
npm run dev
```

请求经 `app/api/backend/[...path]` 代理到 Spring Boot，浏览器侧统一前缀 `/api/backend`。

认证使用短时 access token + 可轮换 refresh token：access token 只保存在页面内存中，refresh token 由后端写入 `HttpOnly`、`SameSite=Lax` Cookie。接口返回 401 时，`app/lib/api.ts` 会单次静默续期并重放原请求；续期失败则清理会话并返回登录页。

## 脚本

| 命令 | 说明 |
|------|------|
| `npm run dev` | 本地开发 |
| `npm run build` | 生产构建 |
| `npm test` | 单测（报告、鉴权、SSE、抽屉交互、样式与素材约束等）+ 生产构建 + 构建后的 SSR 壳层测试 |
| `npm run lint` | ESLint |

## 关键模块

- `app/page.tsx` — 主工作台（认证、CRUD、岗位粗排、分析、收藏、导出）
- `app/workspace-nav.ts` — 所有页面共用的导航定义与 hash 视图映射
- `app/components/AppShell.tsx`、`SideNav.tsx` — 共用壳层与导航
- `app/components/MatchReport.tsx`、`app/components/report/` — 结构化报告、片段引用与原文抽屉
- `app/components/SideDrawer.tsx` — 共用抽屉、键盘关闭与焦点返回
- `app/assistant/page.tsx`、`app/agent-stream.ts`、`app/agent-steps.ts` — 助手 SSE 状态与发送约束
- `app/components/assistant/` — 处理过程、Markdown 回答、确认卡与分析卡
- `app/components/StateBlock.tsx`、`app/view-states.ts` — 空、加载、失败与报告状态
- `app/lib/api.ts` — 统一 API 客户端、401 恢复与静默续期
- `app/report-export.ts` — 匹配报告 Markdown / 打印 PDF HTML
- `app/workspace.css`、`app/components/BrandArt.tsx` — 样式变量与素材统一入口；图片位于 `public/brand/`，中文字体自托管于 `public/fonts/`
