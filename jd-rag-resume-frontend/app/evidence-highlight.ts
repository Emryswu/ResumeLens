/**
 * Which parts of a resume chunk to mark when a finding opens it. This is a word-level heuristic: a mark means the
 * words also appear in the finding, not that the chunk supports the finding.
 */
export type HighlightSegment = { text: string; mark: boolean };

const LATIN_TERM = /[A-Za-z][A-Za-z0-9+#.\-]*[A-Za-z0-9+#]/g;
const CJK_RUN = /[㐀-鿿]+/g;
const MIN_CJK = 2;
/** Words that occur in almost every resume; on their own (or only strung together) they point nowhere useful. */
const GENERIC_CJK = new Set([
  "后端", "前端", "技术", "开发", "经验", "系统", "能力", "项目", "负责", "熟悉", "使用",
  "参与", "相关", "工作", "掌握", "了解", "具备", "实践",
]);

/** True when the piece splits entirely into generic words, e.g. "后端" or "后端开发". */
function isGeneric(piece: string): boolean {
  const ok = [true];
  for (let end = 1; end <= piece.length; end++) {
    ok[end] = Array.from(GENERIC_CJK).some((word) => end >= word.length && ok[end - word.length] && piece.endsWith(word, end));
  }
  return ok[piece.length];
}

function latinRanges(chunk: string, source: string): Array<[number, number]> {
  const terms = new Set(Array.from(source.matchAll(LATIN_TERM), (match) => match[0].toLowerCase()));
  const lower = chunk.toLowerCase();
  const ranges: Array<[number, number]> = [];
  for (const term of terms) {
    let from = 0;
    for (let at = lower.indexOf(term, from); at !== -1; at = lower.indexOf(term, from)) {
      const before = lower[at - 1];
      const after = lower[at + term.length];
      // Whole words only, so "Java" does not light up inside "JavaScript".
      if (!(before && /[a-z0-9]/.test(before)) && !(after && /[a-z0-9]/.test(after))) {
        ranges.push([at, at + term.length]);
      }
      from = at + term.length;
    }
  }
  return ranges;
}

/** Longest runs of the title's Chinese text (two characters or more) that occur verbatim in the chunk. */
function cjkRanges(chunk: string, title: string): Array<[number, number]> {
  const ranges: Array<[number, number]> = [];
  for (const run of Array.from(title.matchAll(CJK_RUN), (match) => match[0])) {
    let start = 0;
    while (start <= run.length - MIN_CJK) {
      let matched = 0;
      for (let end = run.length; end - start >= MIN_CJK; end--) {
        if (chunk.includes(run.slice(start, end))) {
          matched = end - start;
          break;
        }
      }
      if (!matched) {
        start++;
        continue;
      }
      const piece = run.slice(start, start + matched);
      start += matched;
      if (isGeneric(piece)) continue;
      for (let at = chunk.indexOf(piece); at !== -1; at = chunk.indexOf(piece, at + piece.length)) {
        ranges.push([at, at + piece.length]);
      }
    }
  }
  return ranges;
}

export function highlightRanges(chunk: string, finding: { title: string; detail: string }): Array<[number, number]> {
  const ranges = [
    ...latinRanges(chunk, `${finding.title} ${finding.detail}`),
    ...cjkRanges(chunk, finding.title),
  ].sort((a, b) => a[0] - b[0] || b[1] - a[1]);
  const merged: Array<[number, number]> = [];
  for (const [start, end] of ranges) {
    const last = merged[merged.length - 1];
    if (last && start <= last[1]) last[1] = Math.max(last[1], end);
    else merged.push([start, end]);
  }
  return merged;
}

export function highlightSegments(chunk: string, finding: { title: string; detail: string }): HighlightSegment[] {
  const segments: HighlightSegment[] = [];
  let at = 0;
  for (const [start, end] of highlightRanges(chunk, finding)) {
    if (start > at) segments.push({ text: chunk.slice(at, start), mark: false });
    segments.push({ text: chunk.slice(start, end), mark: true });
    at = end;
  }
  if (at < chunk.length) segments.push({ text: chunk.slice(at), mark: false });
  return segments;
}
