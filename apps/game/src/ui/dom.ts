type Child = Node | string | null | undefined | false;
type Props = Record<string, string | number | boolean | ((e: Event) => void) | undefined>;

/** Minimal DOM builder. Text is always set via text nodes (never innerHTML), so user names cannot inject markup. */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, props: Props = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === undefined || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'class') el.className = String(v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, String(v));
  }
  for (const c of children) if (c !== null && c !== undefined && c !== false) el.append(typeof c === 'string' ? document.createTextNode(c) : c);
  return el;
}

export function toast(message: string, kind: 'info' | 'error' | 'reward' = 'info'): void {
  const host = document.getElementById('toasts');
  if (!host) return;
  const t = h('div', { class: `toast ${kind}`, role: 'status' }, message);
  host.append(t);
  setTimeout(() => t.classList.add('out'), 2200);
  setTimeout(() => t.remove(), 2600);
}

export function fmt(n: number): string { return n.toLocaleString('en-US'); }
