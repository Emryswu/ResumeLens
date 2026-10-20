"use client";

import { CircleAlert, LoaderCircle, RotateCw, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { BrandImage, type BrandIllustration } from "./BrandArt";

/**
 * Empty, loading and error blocks shared by every signed-in page, so each state looks and reads the same:
 * an icon, one sentence, and the action that gets the user out of it.
 */

type Size = { compact?: boolean };

export function EmptyState({ icon: Icon, art, title, text, action, compact }: Size & {
  icon: LucideIcon;
  /** A full-page empty state can show an illustration instead of the icon; compact rows keep the icon. */
  art?: BrandIllustration;
  title: string;
  text?: ReactNode;
  /** The main way forward, usually a primary button. */
  action?: ReactNode;
}) {
  return (
    <div className={`state-block${compact ? " compact" : ""}${art && !compact ? " has-art" : ""}`}>
      {art && !compact
        ? <BrandImage name={art} className="state-art" />
        : <span className="state-icon" aria-hidden="true"><Icon size={compact ? 20 : 24} /></span>}
      <div className="state-copy">
        <h3>{title}</h3>
        {text && <p>{text}</p>}
      </div>
      {action && <div className="state-actions">{action}</div>}
    </div>
  );
}

export function LoadingState({ text, compact }: Size & { text: string }) {
  return (
    <div className={`state-block is-loading${compact ? " compact" : ""}`} role="status">
      <span className="state-icon" aria-hidden="true"><LoaderCircle className="spin" size={compact ? 20 : 24} /></span>
      <div className="state-copy"><p>{text}</p></div>
    </div>
  );
}

/** A failure with nothing else to show. Pass onRetry whenever repeating the request can help. */
export function ErrorState({ title, text, onRetry, retryLabel = "重试", retrying, action, compact }: Size & {
  title?: string;
  text: ReactNode;
  onRetry?: () => void;
  retryLabel?: string;
  retrying?: boolean;
  /** Another way out, shown next to retry or instead of it. */
  action?: ReactNode;
}) {
  return (
    <div className={`state-block is-error${compact ? " compact" : ""}`} role="alert">
      <span className="state-icon" aria-hidden="true"><CircleAlert size={compact ? 20 : 24} /></span>
      <div className="state-copy">
        {title && <h3>{title}</h3>}
        <p>{text}</p>
      </div>
      {(onRetry || action) && (
        <div className="state-actions">
          {onRetry && <RetryButton onClick={onRetry} label={retryLabel} busy={retrying} />}
          {action}
        </div>
      )}
    </div>
  );
}

export function RetryButton({ onClick, label = "重试", busy, className = "ghost" }: {
  onClick: () => void;
  label?: string;
  busy?: boolean;
  className?: string;
}) {
  return (
    <button type="button" className={className} disabled={busy} onClick={onClick}>
      <RotateCw className={busy ? "spin" : undefined} size={16} aria-hidden="true" />{label}
    </button>
  );
}

/** Grey placeholder lines with the rough shape of the content; the label is what screen readers hear. */
export function Skeleton({ label, lines = 3, className = "" }: { label: string; lines?: number; className?: string }) {
  return (
    <div className={`skeleton ${className}`} role="status" aria-label={label}>
      {Array.from({ length: lines }, (_, index) => <span key={index} className="skeleton-line" aria-hidden="true" />)}
    </div>
  );
}

/** One line, cut with an ellipsis; the full text stays available on hover and to screen readers. */
export function Truncate({ text, className = "" }: { text: string; className?: string }) {
  return <span className={`truncate ${className}`} title={text}>{text}</span>;
}
