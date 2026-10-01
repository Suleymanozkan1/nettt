import { describe, expect, it } from 'vitest';
import { storage } from '../src/lib/storage';

describe('storage (web fallback)', () => {
  it('round-trips values via localStorage', async () => {
    await storage.set('k', 'v');
    expect(await storage.get('k')).toBe('v');
    await storage.remove('k');
    expect(await storage.get('k')).toBeNull();
  });
});
