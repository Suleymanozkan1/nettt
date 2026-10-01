import { randomBytes } from 'node:crypto';
import type { Redis } from 'ioredis';

const TTL_S = 120;

/** Single-use proof-of-work challenges: Redis when available (multi-instance), else in-process memory. */
export class PowStore {
  private mem = new Map<string, { salt: string; exp: number }>();
  constructor(private readonly redis?: Redis) {}

  async issue(): Promise<{ id: string; salt: string; expiresIn: number }> {
    const id = randomBytes(12).toString('base64url');
    const salt = randomBytes(16).toString('base64url');
    if (this.redis) await this.redis.set(`pow:${id}`, salt, 'EX', TTL_S);
    else {
      const now = Date.now();
      for (const [k, v] of this.mem) if (v.exp < now) this.mem.delete(k);
      this.mem.set(id, { salt, exp: now + TTL_S * 1000 });
    }
    return { id, salt, expiresIn: TTL_S };
  }

  /** Returns the salt and deletes the challenge atomically (a challenge can be spent once). */
  async consume(id: string): Promise<string | null> {
    if (this.redis) return this.redis.getdel(`pow:${id}`);
    const v = this.mem.get(id);
    this.mem.delete(id);
    return v && v.exp >= Date.now() ? v.salt : null;
  }
}
