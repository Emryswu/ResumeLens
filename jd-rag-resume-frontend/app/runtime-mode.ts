/** How the page describes which model answers: never more than the backend says it runs. */
import type { AiStatus } from "./lib/api";

export function configuredModel(status: AiStatus | null) {
  return status?.model.trim() || "模型未配置";
}

export function runtimeModeLabel(status: AiStatus | null) {
  if (!status) return "AI 运行模式确认中";
  return status.mockEnabled
    ? "演示模式（离线 mock），未调用真实模型"
    : `当前生成模型：${configuredModel(status)}`;
}

export function generationProgressLabel(status: AiStatus | null) {
  if (!status) return "生成模式确认中，正在准备分析…";
  return status.mockEnabled
    ? "演示模式（离线 mock），未调用真实模型"
    : `${configuredModel(status)} 正在生成分析…`;
}

export function pendingAnalysisLabel(status: AiStatus | null) {
  if (!status) return "本地检索与生成分析正在进行（运行模式确认中），完成后会自动更新。";
  return status.mockEnabled
    ? "本地检索与离线 mock 演示分析正在进行；演示模式（离线 mock），未调用真实模型。完成后会自动更新。"
    : `本地检索与 ${configuredModel(status)} 分析正在进行，完成后会自动更新。`;
}
