// dom.js — small helpers so rendering code reads as structure, not as
// document.createElement noise. Everything is built as nodes rather than
// innerHTML: model output, failure messages and artifact bodies all flow into
// this UI, and none of them should ever be parsed as markup.

/**
 * Build an element.
 *
 * @param tag     tag name, optionally with a .class.list suffix ("span.pill.p-ok")
 * @param props   attributes; `text` sets textContent, `html` is deliberately absent
 * @param children child nodes or strings, nulls ignored
 */
export function el(tag, props = {}, children = []) {
  const [name, ...classes] = tag.split(".");
  const node = document.createElement(name);
  if (classes.length) node.className = classes.join(" ");

  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined) continue;
    if (key === "text") node.textContent = value;
    else if (key === "class") node.className = `${node.className} ${value}`.trim();
    else if (key.startsWith("on") && typeof value === "function") {
      node.addEventListener(key.slice(2).toLowerCase(), value);
    } else node.setAttribute(key, value);
  }

  for (const child of [].concat(children)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** Replace every child of `node` with `children`. */
export function replace(node, children) {
  node.replaceChildren(...[].concat(children).filter(Boolean));
}

/** A pill. `tier` is one of ok | warn | crit | qual | mute | accent. */
export function pill(tier, label, { led = true } = {}) {
  return el(`span.pill.p-${tier}`, {}, [led ? el("span.led") : null, label]);
}

/** Format an ISO instant pair as elapsed milliseconds, or null when unknown. */
export function elapsedMs(fromIso, toIso) {
  if (!fromIso || !toIso) return null;
  const ms = Date.parse(toIso) - Date.parse(fromIso);
  return Number.isFinite(ms) && ms >= 0 ? ms : null;
}

/** Human-readable duration for a millisecond count. */
export function formatDuration(ms) {
  if (ms === null || ms === undefined) return "—";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(ms / 60000);
  const seconds = Math.round((ms % 60000) / 1000);
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}
