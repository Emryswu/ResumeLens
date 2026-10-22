# 测试样本

- `boss-joblist.sample.json`：BOSS 搜索列表接口（`/wapi/zpgeek/search/joblist.json`）的脱敏响应样本，`tests/boss-api-extractor.test.cjs` 用它做解析快照测试。**当前这份是按接口字段结构手工构造的，还没有用真实抓包核对过**，拿到真实响应后按下面的步骤替换。
- `boss-joblist.expected.json`：上面样本解析后应得到的岗位列表（快照）。

样本里所有字段都经过 `scripts/sanitize-boss-sample.cjs` 处理：只保留职位名、薪资、标签、城市、行业这类不指向具体个人或账号的描述字段，岗位 ID 和公司名替换成占位值，其余字符串一律 `REDACTED`、数字一律 0。测试会检查样本经过脱敏脚本后保持不变，原始抓包直接提交会让测试失败。

更新样本的步骤：

1. 在已登录 BOSS 直聘的浏览器里打开搜索页，开发者工具 → Network，找到 `joblist.json`，把响应保存成本地文件（不要放进仓库目录）。
2. `node scripts/sanitize-boss-sample.cjs <保存的文件> tests/fixtures/boss-joblist.sample.json`
3. 跑 `npm test`。解析结果变了的话，确认变化合理后再更新 `boss-joblist.expected.json`。
