"use client";

import { useMemo } from "react";
import { parseAgentMarkdown, type MdInline } from "../../agent-markdown";

export function AgentMarkdown({ text, className }: { text: string; className: string }) {
  const blocks = useMemo(() => parseAgentMarkdown(text), [text]);
  return (
    <div className={`${className} agent-md`}>
      {blocks.map((block, index) => {
        switch (block.type) {
          case "paragraph":
            return (
              <p key={index}>
                {block.lines.map((line, lineIndex) => (
                  <span key={lineIndex}>{lineIndex > 0 && <br />}<Inline parts={line} /></span>
                ))}
              </p>
            );
          case "heading":
            return <p key={index} className={`agent-md-heading level-${block.level}`}><Inline parts={block.content} /></p>;
          case "list": {
            const items = block.items.map((item, itemIndex) => <li key={itemIndex}><Inline parts={item} /></li>);
            return block.ordered ? <ol key={index} start={block.start}>{items}</ol> : <ul key={index}>{items}</ul>;
          }
          case "table":
            return (
              <div key={index} className="agent-md-table">
                <table>
                  <thead>
                    <tr>{block.header.map((cell, cellIndex) => <th key={cellIndex}><Inline parts={cell} /></th>)}</tr>
                  </thead>
                  <tbody>
                    {block.rows.map((row, rowIndex) => (
                      <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}><Inline parts={cell} /></td>)}</tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          case "code":
            return <pre key={index}><code>{block.text}</code></pre>;
        }
      })}
    </div>
  );
}

function Inline({ parts }: { parts: MdInline[] }) {
  return parts.map((part, index) =>
    part.type === "strong" ? <strong key={index}>{part.text}</strong>
      : part.type === "code" ? <code key={index}>{part.text}</code>
        : part.text,
  );
}
