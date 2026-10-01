import { describe, expect, it } from 'vitest';
import { parseInviteUrl, inviteLinks } from '../src';

describe('duel invite links', () => {
  it('parses native and web invites', () => {
    expect(parseInviteUrl('golgekuklaci://duel/AbC123xyz')).toBe('AbC123xyz');
    expect(parseInviteUrl('https://oyna.example.com/?duel=AbC123xyz')).toBe('AbC123xyz');
    expect(parseInviteUrl('http://localhost:4173/?debug=1&duel=r_9-Kx')).toBe('r_9-Kx');
  });
  it('rejects anything malformed or unexpected', () => {
    for (const bad of [
      'golgekuklaci://shop/AbC123', 'golgekuklaci://duel/', 'golgekuklaci://duel/a', 'golgekuklaci://duel/<script>',
      'golgekuklaci://duel/..%2F..%2Fadmin', 'javascript:alert(1)', 'https://x.io/?duel=' + 'a'.repeat(40),
      'https://x.io/?duel=abc%20def', 'not a url', 'file:///etc/passwd?duel=abcd', '',
    ]) expect(parseInviteUrl(bad)).toBeNull();
  });
  it('builds links that round-trip', () => {
    const { deepLink, webLink } = inviteLinks('Room_42', 'https://oyna.example.com');
    expect(parseInviteUrl(deepLink)).toBe('Room_42');
    expect(parseInviteUrl(webLink)).toBe('Room_42');
  });
});
