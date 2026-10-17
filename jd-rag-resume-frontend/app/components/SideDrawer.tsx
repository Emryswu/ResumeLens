"use client";

import { X } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";

/**
 * Non-modal side panel: on the right on wide screens, a bottom sheet at 780px and below. Escape closes it.
 * Each time it opens or focusKey changes, it remembers what had focus (the control that opened or switched it) and
 * moves focus to the title; closing returns focus to the control that opened it last.
 */
export function SideDrawer({ id, title, subtitle, closeLabel, focusKey, onClose, children }: {
  id: string;
  title: ReactNode;
  subtitle?: ReactNode;
  closeLabel: string;
  /** Identifies the content, e.g. the turn or snippet shown. Change it when the drawer switches to other content. */
  focusKey: unknown;
  onClose: () => void;
  children: ReactNode;
}) {
  const asideRef = useRef<HTMLElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);

  // Runs on open and on every switch, so switching from A to B and closing returns to B, not A.
  // Focus already inside the panel is not an opener.
  useEffect(() => {
    const active = document.activeElement;
    if (active instanceof HTMLElement && active !== document.body && !asideRef.current?.contains(active)) openerRef.current = active;
    headingRef.current?.focus();
  }, [focusKey]);

  useEffect(() => () => {
    const opener = openerRef.current;
    if (opener?.isConnected) opener.focus();
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <aside id={id} ref={asideRef} className="side-drawer" aria-labelledby={`${id}-title`}>
      <header>
        <div>
          <h3 id={`${id}-title`} tabIndex={-1} ref={headingRef}>{title}</h3>
          {subtitle && <p>{subtitle}</p>}
        </div>
        <button type="button" className="ghost icon-only" aria-label={closeLabel} onClick={onClose}><X size={18} aria-hidden="true" /></button>
      </header>
      {children}
    </aside>
  );
}
