# 本地 ChatGPT 订阅额度（开发者预览）

适用于开源项目的本地单人开发与评测。使用官方 Sign in with ChatGPT 动态注册，用户本人授权；不读取 Codex 的登录文件，不需要 DeepSeek API Key，不向第三方地址发送 OAuth 凭据。不支持远程共享或多人托管。原有 Web 应用的 API Key 配置不变，本入口只供本地开发评测。

## Windows：网页登录（推荐）

在仓库根目录（切换至本 PR 分支）执行：

```powershell
py -3 -m venv .venv-chatgpt
$python = (Resolve-Path .venv-chatgpt\Scripts\python.exe).Path
& $python -m pip install -r tools/chatgpt-plan/requirements.txt
& $python tools/chatgpt-plan/chatgpt_plan.py web
```

保持上述本地设置服务运行。在浏览器打开 http://127.0.0.1:8765/，或者在前端 `.env.local` 设置 `NEXT_PUBLIC_LOCAL_CHATGPT_SETTINGS=true` 后重启前端，点击 ResumeLens 右上角的「ChatGPT 订阅设置」。在设置页点击「使用 ChatGPT 登录」，官方授权页将在系统浏览器打开；同意权限后回到设置页选择并保存模型。网页不会读取 OAuth token 或 ChatGPT 聊天记录。授权只用于当前操作系统用户的本地开发评测，不用于共享 Web 后端。

另开一个 PowerShell，在仓库根目录执行：

```powershell
$python = (Resolve-Path .venv-chatgpt\Scripts\python.exe).Path
$env:CHATGPT_PLAN_PYTHON = $python
$env:RUN_CHATGPT_DEV_EVAL = "true"
$env:CHATGPT_DEV_TASK = "v3d01"
cd jd-rag-resume-backend
.\mvnw.cmd -B --no-transfer-progress "-Dtest=LocalChatGptDevEvalTests" test
```

评测默认读取网页中保存的模型，也可显式设置 `CHATGPT_DEV_MODEL` 指定模型。首次只跑一个开发任务以验证授权、Responses、工具调用和结果。删除 `CHATGPT_DEV_TASK` 环境变量后再次执行可跑完整开发集。每完成一题立即写入 `target/chatgpt-dev-eval/run-*.jsonl`。供应商故障/额度用完时立即停止；可以按尚未运行的任务 ID 单独重跑，当前没有自动续跑或跨文件汇总。此入口不会触及 v3 冻结 acceptance、正式登记槽位、RUN-LOG 或评分源码；最新业务代码的正式新基线需单独登记。

```powershell
Remove-Item Env:RUN_CHATGPT_DEV_EVAL -ErrorAction SilentlyContinue
Remove-Item Env:CHATGPT_DEV_TASK -ErrorAction SilentlyContinue
cd ..
& $python tools/chatgpt-plan/chatgpt_plan.py logout
```

## 账号与安全

- 仅支持一个本地账号登记，重新登录复用同一 issued client ID 并验证身份。暂未提供切换多个账号的 UI。设置服务只监听 127.0.0.1:8765，表单校验 Host、Origin 和 CSRF；不要对外映射端口。
- 浏览器 OAuth 使用 127.0.0.1 随机端口、state、nonce、PKCE S256；ID token 验证 JWKS 签名、issuer、audience、expiry、nonce 和已绑定身份。
- 凭据记录保存在用户主目录 `.resumelens-chatgpt/session.enc`，Fernet 密钥在操作系统凭据库中；要求 Windows Credential Manager、macOS Keychain、Linux Secret Service/KWallet 等安全后端，缺少安全后端时显式失败。不要把令牌写进 .env、源码、聊天、日志或 CI。
- Java 子进程内部取得 access token，接近过期自动刷新、串行保存旋转的 refresh token；登出撤销可续期会话并清理本地 tokens（保留登记）。也可以在 ChatGPT Settings 中断开授权。
- 请求固定发往 `https://api.openai.com/v1/responses`，不使用原 API Key、AI_BASE_URL 或非官方中转。额度与 ChatGPT plan usage 共用；不会在额度耗尽时自动切换到付费 API。
- 官方预览不支持部分 Responses 参数，因此模型输出预算/推理强度与旧 DeepSeek 调用并非完全等价；目前采用账号支持的模型及默认推理配置，完整模型/工具兼容性需实际登录验证。

仍可在服务未运行时使用 `login`、`models`、`logout` 命令行入口。网页中的「断开 ChatGPT」也可撤销本机授权。

## 离线测试

```powershell
& $python -m unittest discover -s tools/chatgpt-plan
```

Java 协议单测随原有 Maven verify 执行，真实评测仅在显式设置 RUN_CHATGPT_DEV_EVAL=true 时运行。

官方文档：[Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) · [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) · [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) · [ChatGPT Usage](https://chatgpt.com/settings/usage)
