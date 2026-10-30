import type { AgentTurn } from "./agent-stream";

export type ResumeCitation = { quote: string; supports: string };
export type Recommendation = {
  jobId: number; title: string; company: string; location: string; reason: string;
  resumeId: number; resumeEvidence: ResumeCitation[];
};

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

/** Only server-approved facts feed cards; arguments and clipped previews are never evidence. */
export function approvedRecommendations(turn: Pick<AgentTurn, "steps">): Recommendation[] {
  const approved = new Map<number, Recommendation>();
  for (const step of turn.steps) {
    if (!step.ok || step.tool !== "submit_recommendations") continue;
    const items = step.facts?.recommendations;
    if (!Array.isArray(items)) continue;
    for (const item of items) {
      if (!object(item) || typeof item.jobId !== "number" || typeof item.resumeId !== "number"
          || ![item.title, item.company, item.location, item.reason].every(value => typeof value === "string")
          || !Array.isArray(item.resumeEvidence) || item.resumeEvidence.length === 0
          || !item.resumeEvidence.every(citation => object(citation)
            && typeof citation.quote === "string" && typeof citation.supports === "string")) continue;
      if (!approved.has(item.jobId)) approved.set(item.jobId, item as Recommendation);
    }
  }
  return [...approved.values()];
}
