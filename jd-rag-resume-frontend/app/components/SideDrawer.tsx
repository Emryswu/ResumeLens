"use client";

import { X } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";

/**
 * Non-modal side panel: on the right on wide screens, a bottom sheet at 780px and below. Focus moves to the title
 * whenever focusKey changes, Escape closes it, and closing returns focus to whatever opened it.
 */
export function SideDrawer({ id, title, subtitle, closeLabel, focusKey, onClose, children }: {
  id: string;
  title: ReactNode;
  subtitle?: ReactNode;
  closeLabel: string;
  /** Change it to move focus back to the title, e.g. when the drawer switches to other content. */
  focusKey?: unknown;
  onClose: () => void;
  children: ReactNode;
}) {
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    return () => { if (opener?.isConnected) opener.focus(); };
  }, []);

  useEffect(() => {
    headingRef.current?.focus();
  }, [focusKey]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <aside id={id} className="side-drawer" aria-labelledby={`${id}-title`}>
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
