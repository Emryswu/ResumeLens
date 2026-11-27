import type { ReasoningEffort } from "./agent-stream";

const MODEL_DISPLAY_NAMES: Readonly<Record<string, string>> = Object.assign(
  Object.create(null) as Record<string, string>, { "deepseek-flash": "DeepSeek Flash" });

export function modelDisplayName(id: string | null | undefined): string {
  return id ? (Object.hasOwn(MODEL_DISPLAY_NAMES, id) ? MODEL_DISPLAY_NAMES[id] : id) : "模型加载中";
}

export const REASONING_OPTIONS: readonly { value: ReasoningEffort; label: string; description: string }[] = [
  { value: "low", label: "低", description: "少量思考，兼顾速度与判断" },
  { value: "high", label: "高", description: "深入思考，适合比较与分析" },
  { value: "max", label: "最高", description: "更慢，适合复杂规划" },
];

/** API-documented capabilities; unrecognized subscription model IDs keep their provider default. */
const GPT_FIVE_EFFORTS = new Set([
  "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-sol",
  "gpt-6-astra", "gpt-6-luna", "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra",
]);

const GPT_REASONING_OPTIONS: typeof REASONING_OPTIONS = [
  { value: "low", label: "低", description: "Low" },
  { value: "medium", label: "中", description: "Medium" },
  { value: "high", label: "高", description: "High" },
  { value: "xhigh", label: "极高", description: "Extra High" },
  { value: "max", label: "最高", description: "Max" },
];

export function reasoningOptions(model: string): typeof REASONING_OPTIONS {
  if (!model) return REASONING_OPTIONS;
  return GPT_FIVE_EFFORTS.has(model.toLowerCase()) ? GPT_REASONING_OPTIONS : [];
}

export function subscriptionEffort(model: string, saved: string | null): ReasoningEffort {
  const options = reasoningOptions(model);
  return options.find((option) => option.value === saved)?.value ?? "default";
}
