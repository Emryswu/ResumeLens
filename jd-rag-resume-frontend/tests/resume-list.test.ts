import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  RESUME_ACCEPT,
  RESUME_MAX_BYTES,
  formatFileSize,
  relativeDay,
  resumeFileKind,
  resumeFileProblem,
  resumeStats,
} from "../app/resume-list.ts";

const testsDir = dirname(fileURLToPath(import.meta.url));

test("上传前校验格式、空文件和 20MB 上限", () => {
  assert.equal(resumeFileProblem({ name: "简历.PDF", size: 1000 }), null);
  assert.equal(resumeFileProblem({ name: "resume.md", size: 10 }), null);
  assert.match(resumeFileProblem({ name: "简历.png", size: 1000 }) ?? "", /只支持/);
  assert.match(resumeFileProblem({ name: "没有扩展名", size: 1000 }) ?? "", /只支持/);
  assert.match(resumeFileProblem({ name: "a.pdf", size: 0 }) ?? "", /空的/);
  assert.equal(resumeFileProblem({ name: "a.pdf", size: RESUME_MAX_BYTES }), null);
  assert.match(resumeFileProblem({ name: "a.pdf", size: RESUME_MAX_BYTES + 1 }) ?? "", /20MB/);
});

test("前端的格式与大小上限和后端配置一致", () => {
  const properties = readFileSync(join(testsDir, "../../jd-rag-resume-backend/src/main/resources/application.properties"), "utf8");
  assert.match(properties, /spring\.servlet\.multipart\.max-file-size=20MB/);
  assert.equal(RESUME_ACCEPT, ".pdf,.doc,.docx,.txt,.md");
});

test("文件标记按扩展名分色，粘贴保存的简历标为文本", () => {
  assert.deepEqual(resumeFileKind({ fileExtension: "pdf" }), { label: "PDF", tone: "pdf" });
  assert.deepEqual(resumeFileKind({ fileExtension: ".DOCX" }), { label: "DOCX", tone: "word" });
  assert.deepEqual(resumeFileKind({ originalFileName: "简历.md" }), { label: "MD", tone: "text" });
  assert.deepEqual(resumeFileKind({}), { label: "文本", tone: "paste" });
});

test("文件大小读法", () => {
  assert.equal(formatFileSize(undefined), "");
  assert.equal(formatFileSize(0), "");
  assert.equal(formatFileSize(800), "800 B");
  assert.equal(formatFileSize(182000), "178 KB");
  assert.equal(formatFileSize(3 * 1024 * 1024), "3.0 MB");
});

test("相对日期按自然日算，不按 24 小时", () => {
  const now = new Date(2026, 9, 20, 9, 0).getTime();
  assert.equal(relativeDay(new Date(2026, 9, 20, 1, 0).getTime(), now), "今天");
  assert.equal(relativeDay(new Date(2026, 9, 19, 23, 0).getTime(), now), "昨天");
  assert.equal(relativeDay(new Date(2026, 9, 18, 10, 0).getTime(), now), "2 天前");
  assert.equal(relativeDay(new Date(2026, 7, 1).getTime(), now), new Date(2026, 7, 1).toLocaleDateString("zh-CN"));
});

test("统计只用后端真有的字段：份数、上传文件数、最近更新", () => {
  const now = new Date(2026, 9, 20, 9, 0).getTime();
  assert.deepEqual(resumeStats([], now), { count: 0, uploaded: 0, latest: null });
  const stats = resumeStats([
    { createdAt: new Date(2026, 9, 1).toISOString(), originalFileName: "a.pdf" },
    { updatedAt: new Date(2026, 9, 18, 12).toISOString(), createdAt: new Date(2026, 9, 2).toISOString() },
    { updatedAt: "not a date" },
  ], now);
  assert.deepEqual(stats, { count: 3, uploaded: 1, latest: "2 天前" });
});
