import type { ReasoningEffort } from "./agent-stream";

const MODEL_DISPLAY_NAMES: Readonly<Record<string, string>> = {
  "deepseek-flash": "DeepSeek Flash",
};

export function modelDisplayName(id: string | null | undefined): string {
  return id ? MODEL_DISPLAY_NAMES[id] ?? id : "模型加载中";
}

export const REASONING_OPTIONS: readonly { value: ReasoningEffort; label: string; description: string }[] = [
  { value: "low", label: "低", description: "少量思考，兼顾速度与判断" },
  { value: "high", label: "高", description: "深入思考，适合比较与分析" },
  { value: "max", label: "最高", description: "更慢，适合复杂规划" },
];
