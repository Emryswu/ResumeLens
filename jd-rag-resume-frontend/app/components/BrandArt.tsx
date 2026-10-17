/**
 * Atmosphere images from public/brand. They are pre-compressed webp files, so a plain lazy <img> is enough:
 * vinext serves public/ as static assets without the next/image optimizer.
 */
type BrandFile = "hero-landscape" | "sidebar-art" | "cta-card-bg" | "slogan";

export function BrandImage({ name, alt = "", className }: {
  name: BrandFile;
  /** Empty for decoration; the slogan carries words, so it passes them. */
  alt?: string;
  className?: string;
}) {
  // eslint-disable-next-line @next/next/no-img-element -- see the note above
  return <img className={className} src={`/brand/${name}.webp`} alt={alt} loading="lazy" decoding="async" />;
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
