// markdown.js — a small, deliberately partial markdown renderer.
//
// Council answers are model output, and models write markdown whether or not
// anyone asked. Rendering it raw leaves `##` and `**` littering the one piece
// of text a reader actually reads.
//
// This builds DOM nodes and never touches innerHTML. That constraint is the
// whole reason this file exists rather than a dependency: the text passing
// through here is untrusted model output, and there is no bundler to vet a
// third-party library with. Every value below reaches the page as textContent,
// so a model emitting `<script>` or `<img onerror=...>` renders those as the
// literal characters they are.
//
// Supported, because it is what models actually emit: ATX headings, fenced and
// inline code, unordered and ordered lists, blockquotes, horizontal rules,
// bold, italic, and links. Deliberately absent: tables, images, HTML
// passthrough, reference links, nested lists. Anything unrecognised falls
// through as plain text rather than being dropped — losing a line of an answer
// would be far worse than rendering it unstyled.

import { el } from "./dom.js";

// ── Inline

const INLINE = [
  // Order matters: code first, so emphasis inside a span of code is left alone.
  { re: /`([^`]+)`/, render: (m) => el("code", { text: m[1] }) },
  { re: /\*\*([^*]+)\*\*/, render: (m) => el("strong", { text: m[1] }) },
  { re: /__([^_]+)__/, render: (m) => el("strong", { text: m[1] }) },
  { re: /(?<![*\w])\*([^*\n]+)\*(?!\*)/, render: (m) => el("em", { text: m[1] }) },
  { re: /(?<![_\w])_([^_\n]+)_(?!_)/, render: (m) => el("em", { text: m[1] }) },
  {
    re: /\[([^\]]+)\]\(([^)\s]+)\)/,
    render: (m) => {
      // Only http(s) survives. A javascript: or data: URL in model output is
      // not a link anyone asked for, so it degrades to its own text.
      const safe = /^https?:\/\//i.test(m[2]);
      return safe
        ? el("a", { href: m[2], rel: "noopener noreferrer", target: "_blank", text: m[1] })
        : document.createTextNode(m[0]);
    },
  },
];

/**
 * Render one line of inline markdown to a list of nodes.
 *
 * @param text a single line of markdown
 * @returns an array of Nodes
 */
export function renderInline(text) {
  if (!text) return [];

  let earliest = null;
  for (const rule of INLINE) {
    const match = rule.re.exec(text);
    if (match && (earliest === null || match.index < earliest.match.index)) {
      earliest = { rule, match };
    }
  }
  if (!earliest) return [document.createTextNode(text)];

  const { rule, match } = earliest;
  return [
    ...(match.index > 0 ? [document.createTextNode(text.slice(0, match.index))] : []),
    rule.render(match),
    ...renderInline(text.slice(match.index + match[0].length)),
  ];
}

// ── Block

const HEADING = /^(#{1,6})\s+(.*)$/;
const BULLET = /^\s*[-*+]\s+(.*)$/;
const ORDERED = /^\s*(\d+)[.)]\s+(.*)$/;
const QUOTE = /^\s*>\s?(.*)$/;
const RULE = /^\s*(?:---+|\*\*\*+|___+)\s*$/;
const FENCE = /^\s*```(.*)$/;

/**
 * Render markdown to a container element.
 *
 * @param text the markdown source; null or empty yields an empty container
 * @returns an element holding the rendered blocks
 */
export function renderMarkdown(text) {
  const root = el("div.md");
  if (!text) return root;

  const lines = text.split("\n");
  let i = 0;
  let paragraph = [];

  const flushParagraph = () => {
    if (!paragraph.length) return;
    root.append(el("p", {}, renderInline(paragraph.join(" "))));
    paragraph = [];
  };

  while (i < lines.length) {
    const line = lines[i];

    const fence = FENCE.exec(line);
    if (fence) {
      flushParagraph();
      const body = [];
      i += 1;
      while (i < lines.length && !FENCE.test(lines[i])) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1; // closing fence, or end of input for an unterminated block
      root.append(el("pre.md-code", {}, [el("code", { text: body.join("\n") })]));
      continue;
    }

    if (!line.trim()) {
      flushParagraph();
      i += 1;
      continue;
    }

    if (RULE.test(line)) {
      flushParagraph();
      root.append(el("hr.md-rule"));
      i += 1;
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) {
      flushParagraph();
      // Answers are rendered inside a turn, so the document's own hierarchy
      // starts lower down: an h1 in model output is not a page title.
      const level = Math.min(heading[1].length + 2, 6);
      root.append(el(`h${level}.md-h`, {}, renderInline(heading[2])));
      i += 1;
      continue;
    }

    if (QUOTE.test(line)) {
      flushParagraph();
      const body = [];
      while (i < lines.length && QUOTE.test(lines[i])) {
        body.push(QUOTE.exec(lines[i])[1]);
        i += 1;
      }
      root.append(el("blockquote.md-quote", {}, renderInline(body.join(" "))));
      continue;
    }

    if (BULLET.test(line) || ORDERED.test(line)) {
      flushParagraph();
      const ordered = !BULLET.test(line) && ORDERED.test(line);
      const items = [];
      while (i < lines.length) {
        const bullet = BULLET.exec(lines[i]);
        const numbered = ORDERED.exec(lines[i]);
        if (ordered && numbered) items.push(numbered[2]);
        else if (!ordered && bullet) items.push(bullet[1]);
        else break;
        i += 1;
      }
      root.append(el(ordered ? "ol.md-list" : "ul.md-list", {},
        items.map((item) => el("li", {}, renderInline(item)))));
      continue;
    }

    paragraph.push(line);
    i += 1;
  }

  flushParagraph();
  return root;
}
