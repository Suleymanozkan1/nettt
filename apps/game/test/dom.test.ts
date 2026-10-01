import { describe, expect, it, vi } from 'vitest';
import { h, toast, fmt } from '../src/ui/dom';

describe('dom helper', () => {
  it('renders user text as text, never as HTML (XSS-safe)', () => {
    const el = h('li', {}, '<img src=x onerror=alert(1)>');
    expect(el.querySelector('img')).toBeNull();
    expect(el.textContent).toBe('<img src=x onerror=alert(1)>');
  });

  it('wires events and boolean attributes', () => {
    const fn = vi.fn();
    const b = h('button', { onclick: fn, disabled: false, 'data-x': 1 }, 'ok');
    b.click();
    expect(fn).toHaveBeenCalledOnce();
    expect(b.hasAttribute('disabled')).toBe(false);
    expect(b.dataset.x).toBe('1');
  });

  it('shows toasts and formats numbers', () => {
    document.body.innerHTML = '<div id="toasts"></div>';
    toast('hello', 'reward');
    expect(document.querySelector('.toast.reward')?.textContent).toBe('hello');
    expect(fmt(12345)).toBe('12,345');
  });
});
