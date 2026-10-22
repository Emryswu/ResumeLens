-- 岗位来自哪种抓取：FULL 是完整 JD（手动录入、导入、插件在详情页抓取），
-- LISTING 是 AI 助手从搜索列表采集的摘要。已有记录都是完整 JD。
-- 摘要不能覆盖完整 JD：再次在列表里看到同一岗位时只更新 last_seen_at。
ALTER TABLE job_description
    ADD COLUMN capture_depth VARCHAR(16) NOT NULL DEFAULT 'FULL';
