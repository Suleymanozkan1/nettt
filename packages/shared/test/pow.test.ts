import { describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { sha256, leadingZeroBits, checkPow, solvePow } from '../src';

describe('proof of work', () => {
  it('sha256 matches node:crypto', () => {
    for (const m of ['', 'abc', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), 'ğüşıöç:123', 'x'.repeat(1000)]) {
      expect(Buffer.from(sha256(m)).toString('hex')).toBe(createHash('sha256').update(m).digest('hex'));
    }
  });
  it('counts leading zero bits', () => {
    expect(leadingZeroBits(new Uint8Array([0, 0x0f]))).toBe(12);
    expect(leadingZeroBits(new Uint8Array([0x80]))).toBe(0);
  });
  it('solves and verifies; rejects wrong or malformed nonces', () => {
    const nonce = solvePow('salt-1', 10);
    expect(checkPow('salt-1', nonce, 10)).toBe(true);
    expect(checkPow('salt-2', nonce, 16)).toBe(false);
    expect(checkPow('salt-1', '-1', 0)).toBe(false);
    expect(checkPow('salt-1', '1e5', 0)).toBe(false);
  });
});
