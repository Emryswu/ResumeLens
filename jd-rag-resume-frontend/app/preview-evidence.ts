export const PREVIEW_EVIDENCE = [
  { index: 0, section: "技能", sim: 0.78, text: "熟练掌握 Java、Spring Boot、MySQL、JWT 与 REST API 开发；有权限体系与全局异常处理实践。", boost: "Java, Spring Boot" },
  { index: 1, section: "项目", sim: 0.71, text: "负责简历匹配系统中的 RAG 模块：文本分块、本地 Embedding、Top-K 召回与证据拼装。", boost: "RAG, Embedding" },
  { index: 2, section: "工作经历", sim: 0.63, text: "维护招聘业务服务，设计简历/职位表结构，完成用户数据隔离与 JWT 鉴权。", boost: "MySQL" },
  { index: 5, section: "其他", sim: 0.21, text: "兴趣爱好：篮球、摄影。与岗位核心要求相关度较低。", boost: "" },
];

export const PREVIEW_STRENGTHS: Record<number, string> = {
  0: "Java / Spring Boot / MySQL 与 JD 对齐",
  1: "有 RAG 检索链路落地经验",
  2: "熟悉 JWT 与数据隔离",
};

const PREVIEW_ASPECTS: Record<number, string> = {
  0: "Java 后端",
  1: "RAG 工程化",
  2: "JWT 与数据隔离",
};

export function annotatePreviewEvidence(minSimilarity: number | undefined) {
  return PREVIEW_EVIDENCE.map((item) => {
    if (typeof minSimilarity !== "number") {
      return { ...item, status: "待对照当前阈值", kept: false };
    }
    const kept = item.sim >= minSimilarity;
    return { ...item, status: kept ? "进入 prompt" : "低于阈值", kept };
  });
}

function joinAspects(aspects: string[]) {
  if (aspects.length === 1) return aspects[0];
  return `${aspects.slice(0, -1).join("、")}与${aspects[aspects.length - 1]}`;
}

export function previewConclusion(
  items: Array<{ index: number; kept: boolean }>,
  minSimilarity: number | undefined,
) {
  if (typeof minSimilarity !== "number") {
    return "当前服务端阈值尚未读到，示例结论暂不下判断，也不把未对照阈值的片段当作过阈证据。";
  }
  const keptAspects = items
    .filter((item) => item.kept)
    .map((item) => PREVIEW_ASPECTS[item.index])
    .filter((aspect): aspect is string => Boolean(aspect));
  if (keptAspects.length === 0) {
    return `当前阈值 ${minSimilarity.toFixed(2)} 下没有过阈示例块，因此不根据已过滤片段下匹配结论。`;
  }
  return `基于过阈证据，你的简历在${joinAspects(keptAspects)}方面匹配较好。低于阈值的片段不进入结论。`;
}
