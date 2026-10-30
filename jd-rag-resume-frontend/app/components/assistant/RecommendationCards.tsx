import type { AgentTurn } from "../../agent-stream";
import { approvedRecommendations } from "../../agent-recommendations";

export function RecommendationCards({ turn }: { turn: Pick<AgentTurn, "steps"> }) {
  return approvedRecommendations(turn).map(item => (
    <article className="agent-recommendation" key={item.jobId} aria-label={`推荐岗位：${item.title}`}>
      <h3>{item.title}</h3>
      <p className="review-muted">{item.company} · {item.location}</p>
      <p>{item.reason}</p>
      {item.resumeEvidence.map((citation, index) => (
        <section className="review-evidence source-chunk" key={index} aria-label="简历证据">
          <p className="evidence-drawer-for"><span>对应 JD 要求</span>{citation.supports}</p>
          <p className="review-muted">简历原文</p>
          <p className="source-text">{citation.quote}</p>
        </section>
      ))}
    </article>
  ));
}
