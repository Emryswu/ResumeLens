import type { NavIconName } from "../workspace-nav";

/** Stroke icons drawn in currentColor; decorative, the nav label carries the meaning. */
const PATHS: Record<NavIconName, string> = {
  match: "M4 7h11l-3-3M16 13H5l3 3",
  resume: "M6 3h6l3 3v11H6zM12 3v3h3M8.5 10h4M8.5 13h4",
  jobs: "M3.5 7h13v9h-13zM7.5 7V5h5v2M3.5 11h13",
  saved: "M6 3.5h8v13l-4-3-4 3z",
  history: "M10 4a6 6 0 1 1-6 6M4 5v5h5M10 7v3l2 2",
  assistant: "M10 3l1.6 4.4L16 9l-4.4 1.6L10 15l-1.6-4.4L4 9l4.4-1.6zM15.5 14l.6 1.4 1.4.6-1.4.6-.6 1.4-.6-1.4-1.4-.6 1.4-.6z",
};

export function NavIcon({ name }: { name: NavIconName }) {
  return (
    <svg className="nav-icon" viewBox="0 0 20 20" width="20" height="20" aria-hidden="true" focusable="false">
      <path d={PATHS[name]} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
