/**
 * Atmosphere images from public/brand. They are pre-compressed webp files, so a plain <img> is enough:
 * vinext serves public/ as static assets without the next/image optimizer.
 */
type BrandFile = "hero-landscape" | "sidebar-art" | "cta-card-bg" | "slogan";

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
