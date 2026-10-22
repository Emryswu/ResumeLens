/**
 * The sample resume and job behind 「试试示例数据」 on the home page and 「保存示例并分析」 in the workbench.
 * The request function is passed in so tests can run this without a backend.
 */
import type { Analysis, Job, Resume } from "./lib/api";

export type SampleRequest = <T>(path: string, init?: RequestInit) => Promise<T>;

export const SAMPLE_RESUME = {
  title: "Java 后端开发简历",
  candidateName: "张三",
  phone: "13800000000",
  email: "zhangsan@example.com",
  rawText: `技能
熟练掌握 Java、Spring Boot、MySQL、JWT 与 REST API 开发；有权限体系与全局异常处理实践。

工作经历
2023-2025 某互联网公司后端开发：维护招聘业务服务，设计简历/职位表结构，完成用户数据隔离；参与登录鉴权改造，引入 JWT。

项目经历
负责简历匹配系统中的 RAG 模块：文本分块、本地 Embedding、Top-K 召回与证据拼装，支持中英文；对接 DeepSeek Chat Completions，约束 JSON 输出。
使用 Apache Tika 解析 PDF/DOCX 简历文本，并提供 rawText 校对入口。

教育
本科 · 计算机科学与技术 · 主修数据结构、操作系统、计算机网络。`,
};

export const SAMPLE_JOB = {
  title: "RAG 平台工程师",
  companyName: "某科技",
  location: "杭州",
  employmentType: "全职",
  description: "负责企业知识库与招聘场景的 RAG 链路建设，覆盖解析、分块、向量检索与生成式分析，和业务一起把匹配结果做得可解释。",
  requirements: "Java / Spring Boot / MySQL；熟悉 Embedding、向量检索；有 JWT/权限与 API 设计经验；加分：DeepSeek/OpenAI 对接、简历解析。",
};

/** Saves the sample resume, then the sample job, in the signed-in account. */
export async function saveSampleData(request: SampleRequest): Promise<{ resume: Resume; job: Job }> {
  const resume = await request<Resume>("/api/resumes", { method: "POST", body: JSON.stringify(SAMPLE_RESUME) });
  const job = await request<Job>("/api/job-descriptions", { method: "POST", body: JSON.stringify(SAMPLE_JOB) });
  return { resume, job };
}

/** Starts a full analysis of the pair; the result is PENDING until the report is written. */
export function submitAnalysis(request: SampleRequest, resumeId: number, jobId: number): Promise<Analysis> {
  return request<Analysis>("/api/analysis-histories/ai", {
    method: "POST",
    body: JSON.stringify({ resumeId, jobDescriptionId: jobId }),
  });
}
