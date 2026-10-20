import type { HashView } from "../workspace-nav";

/**
 * Atmosphere images from public/brand. They are pre-compressed webp files, so a plain <img> is enough:
 * vinext serves public/ as static assets without the next/image optimizer.
 */
export type BrandFile =
  | "hero-landscape" | "sidebar-art" | "cta-card-bg" | "login-landscape"
  | "slogan" | "slogan-resumes" | "slogan-jobs" | "slogan-history"
  | "illus-workbench" | "empty-resumes" | "empty-jobs" | "empty-history";

/** Small pictures for empty pages and the workbench; decoration only, the text next to them says it all. */
export type BrandIllustration = Extract<BrandFile, "illus-workbench" | `empty-${string}`>;

/** The handwritten line in each page banner. The words are in the picture, so they travel as alt text. */
export const PAGE_SLOGANS: Record<HashView, { file: Extract<BrandFile, `slogan${string}`>; alt: string }> = {
  match: { file: "slogan", alt: "不只是匹配，更是看见你的潜力" },
  resumes: { file: "slogan-resumes", alt: "一份好简历，让更多好机会找到你" },
  jobs: { file: "slogan-jobs", alt: "不只是找工作，而是找到更适合你的舞台" },
  saved: { file: "slogan-jobs", alt: "不只是找工作，而是找到更适合你的舞台" },
  history: { file: "slogan-history", alt: "每一次分析，都是更靠近理想职业的一步" },
};

export function BrandImage({ name, alt = "", className, priority = false }: {
  name: BrandFile;
  /** Empty for decoration; the slogan carries words, so it passes them. */
  alt?: string;
  className?: string;
  /** In the first screen: load right away at high priority. Everything else waits until it scrolls near. */
  priority?: boolean;
}) {
  return (
    // eslint-disable-next-line @next/next/no-img-element -- see the note above
    <img className={className} src={`/brand/${name}.webp`} alt={alt} decoding="async"
      loading={priority ? undefined : "lazy"} fetchPriority={priority ? "high" : undefined} />
  );
}

export function SidebarArtCard() {
  return (
    <div className="sidebar-card">
      <BrandImage name="sidebar-art" />
      <p>用 AI 放大你的职业可能</p>
    </div>
  );
}

export function BrandCtaCard() {
  return (
    <div className="brand-cta">
      <BrandImage name="cta-card-bg" />
      <strong>让每一份简历<br />都被看见真正的价值</strong>
      <small>ResumeLens</small>
    </div>
  );
}
