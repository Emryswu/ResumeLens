# ResumeLens 岗位助手（Chrome MV3）

第一版支持在 BOSS 直聘职位页抓取 JD、手动校正、选择 ResumeLens 简历、启动 RAG 匹配分析，并在扩展里显示结果。岗位会写入 ResumeLens 的个人岗位库；相同 BOSS 岗位再次出现时按来源 ID 查重，并优先复用同一份简历的已有分析。

## 本地安装

1. 启动 ResumeLens 后端（8080）和前端（3000），在 `http://localhost:3000` 登录并至少保存一份简历。
2. 打开 `chrome://extensions`，开启“开发者模式”，选择“加载已解压的扩展程序”。
3. 选择本目录 `browser-extension/`。
4. 打开一个 BOSS 直聘职位详情页，点击工具栏里的“ResumeLens 岗位助手”。

扩展不会保存账号密码或刷新令牌。它通过已登录的 ResumeLens 网页调用同源 BFF，因此使用时需要保留一个已登录的 ResumeLens 标签页。扩展只长期拥有本机 ResumeLens 地址的访问权限；BOSS 页面权限通过 `activeTab` 在点击扩展时临时授予，不会在后台常驻读取 BOSS 页面。

BOSS 页面结构变动时，扩展会保留可编辑表单并提示缺失字段；不要在字段不完整时直接提交。若页面没有稳定岗位 ID，扩展会根据完整岗位内容生成兜底指纹，岗位内容变化后会被视为新岗位，避免把两个相似职位错误合并。

## AI 助手采集（只读）

授权后，ResumeLens 网页可以让插件在这个浏览器里打开 BOSS 搜索页、滚动加载并读取岗位列表。插件不会点击「立即沟通」，也不会发送任何消息。

- **授权**：BOSS 权限是可选权限（`optional_host_permissions`），默认没有。要在插件弹窗里点「允许访问 BOSS 直聘」才会生效，同一个位置可以撤销。未授权时，采集指令会直接返回 `PERMISSION_REQUIRED`，不会打开任何页面。
- **指令只能来自 ResumeLens 网页**：网页通过 `window.postMessage` 发给本机 ResumeLens 页上的内容脚本，内容脚本再通过 port 转给 service worker。service worker 只接受来自 `localhost:3000` 和 `127.0.0.1:3000` 的连接。搜索 URL 由插件根据「关键词＋城市白名单」自己拼出来，不接受外部传入的 URL。
- **节奏**：每条指令最多 3 页、最多 45 条。每多读一页之前，随机等待 3–7 秒。
- **遇到这些情况立即停止，不重试，已经读到的岗位会保留**：未登录、安全验证、页面结构无法识别、页面加载超时、标签页被关闭、跳转到无法读取的页面。停止原因以 `stopReason` 返回。
- **进度和结果**：通过 port 持续推送，任务状态每一步都写入 `chrome.storage.session`。如果 service worker 在采集途中被浏览器重启，port 会断开，内容脚本随即重新连接并查询任务状态，新的 worker 会把它报告为 `WORKER_RESTARTED`。
- **列表卡片只有摘要**：描述字段会写明「BOSS 搜索列表摘要，未读取完整职位描述」。岗位 ID 与详情页抓取使用同一套规则，所以之后在详情页用弹窗抓取时，会落到同一条记录上。没有稳定岗位 ID 的卡片会被跳过，并计入 `skippedWithoutId`。

## 验证

```powershell
npm test
npm run pack
```

`npm run pack` 只把 `manifest.json` 和运行所需的 `src/` 打进 `dist/resumelens-browser-extension-v<版本>.zip`，可解压后按上面的步骤安装。测试覆盖 BOSS 岗位 ID、规范化 URL、字段提取、最小权限、弹窗请求失效保护，以及采集指令校验、列表解析、停止原因、限速、取消和 worker 重启后的状态恢复。完整端到端验证还需要真实登录态、BOSS 页面、MySQL、Embedding 模型与生成模型同时可用。
