/**
 * The subset of Markdown real models actually write in assistant replies: paragraphs, headings,
 * bullet / numbered lists, pipe tables, fenced code, **bold** and `code`. The result is a plain
 * tree rendered as React elements, never as HTML, so text quoted from resumes or job pages
 * cannot inject markup.
 */
export type MdInline =
  | { type: "text"; text: string }
  | { type: "strong"; text: string }
  | { type: "code"; text: string };

export type MdBlock =
  | { type: "paragraph"; lines: MdInline[][] }
  | { type: "heading"; level: 1 | 2 | 3; content: MdInline[] }
  | { type: "list"; ordered: boolean; start: number; items: MdInline[][] }
  | { type: "table"; header: MdInline[][]; rows: MdInline[][][] }
  | { type: "code"; text: string };

const FENCE = /^\s*```/;
const HEADING = /^(#{1,6})\s+(.+?)\s*#*\s*$/;
const BULLET = /^\s*[-*•]\s+(.*)$/;
// A separator and a space are required, so "1.8s 慢查询" or "5 年经验" stay ordinary text.
const NUMBERED = /^\s*(\d{1,3})[.)、]\s+(.*)$/;
const TABLE_SEPARATOR = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;
const RULE = /^\s*([-*_])(\s*\1){2,}\s*$/;

export function parseAgentMarkdown(source: string): MdBlock[] {
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  const blocks: MdBlock[] = [];
  let paragraph: string[] = [];

  const flushParagraph = () => {
    if (paragraph.length > 0) blocks.push({ type: "paragraph", lines: paragraph.map(parseInline) });
    paragraph = [];
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    if (FENCE.test(line)) {
      flushParagraph();
      const body: string[] = [];
      i++;
      while (i < lines.length && !FENCE.test(lines[i])) body.push(lines[i++]);
      blocks.push({ type: "code", text: body.join("\n") });
      continue;
    }

    if (line.includes("|") && i + 1 < lines.length && TABLE_SEPARATOR.test(lines[i + 1]) && lines[i + 1].includes("-")) {
      flushParagraph();
      const header = splitRow(line);
      const rows: MdInline[][][] = [];
      i += 2;
      while (i < lines.length && lines[i].includes("|") && lines[i].trim() !== "") {
        const cells = splitRow(lines[i]);
        // Pad or trim so every row lines up with the header.
        rows.push(header.map((_, column) => cells[column] ?? []));
        i++;
      }
      i--;
      blocks.push({ type: "table", header, rows });
      continue;
    }

    if (line.trim() === "" || RULE.test(line)) {
      flushParagraph();
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      flushParagraph();
      blocks.push({ type: "heading", level: Math.min(heading[1].length, 3) as 1 | 2 | 3, content: parseInline(heading[2]) });
      continue;
    }

    const bullet = BULLET.exec(line);
    const numbered = bullet ? null : NUMBERED.exec(line);
    if (bullet || numbered) {
      flushParagraph();
      const ordered = numbered != null;
      const text = bullet ? bullet[1] : numbered![2];
      const previous = blocks[blocks.length - 1];
      if (previous?.type === "list" && previous.ordered === ordered && lines[i - 1]?.trim() !== "") {
        previous.items.push(parseInline(text));
      } else {
        blocks.push({ type: "list", ordered, start: numbered ? Number(numbered[1]) : 1, items: [parseInline(text)] });
      }
      continue;
    }

    // An indented line right under a list item continues that item rather than starting a paragraph.
    const previous = blocks[blocks.length - 1];
    if (paragraph.length === 0 && previous?.type === "list" && /^\s+\S/.test(line) && lines[i - 1]?.trim() !== "") {
      const last = previous.items[previous.items.length - 1];
      last.push({ type: "text", text: " " }, ...parseInline(line.trim()));
      continue;
    }

    paragraph.push(line.trim());
  }
  flushParagraph();
  return blocks;
}

function splitRow(line: string): MdInline[][] {
  let row = line.trim();
  if (row.startsWith("|")) row = row.slice(1);
  if (row.endsWith("|")) row = row.slice(0, -1);
  return row.split("|").map((cell) => parseInline(cell.trim()));
}

export function parseInline(text: string): MdInline[] {
  const parts: MdInline[] = [];
  let rest = text;
  const pushText = (value: string) => {
    if (!value) return;
    const last = parts[parts.length - 1];
    if (last?.type === "text") last.text += value;
    else parts.push({ type: "text", text: value });
  };

  while (rest.length > 0) {
    const match = /\*\*(.+?)\*\*|`([^`]+)`/.exec(rest);
    if (!match) {
      pushText(rest);
      break;
    }
    pushText(rest.slice(0, match.index));
    if (match[1] !== undefined) parts.push({ type: "strong", text: match[1] });
    else parts.push({ type: "code", text: match[2] });
    rest = rest.slice(match.index + match[0].length);
  }
  return parts;
}
