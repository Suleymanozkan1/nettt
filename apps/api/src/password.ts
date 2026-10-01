import { randomBytes, scrypt as scryptCb, timingSafeEqual, createHash } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCb) as (pw: string, salt: Buffer, len: number, opts: { N: number; r: number; p: number }) => Promise<Buffer>;
const PARAMS = { N: 16384, r: 8, p: 1 };

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const hash = await scrypt(password, salt, 64, PARAMS);
  return `scrypt$${PARAMS.N}$${salt.toString('base64')}$${hash.toString('base64')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [algo, n, saltB64, hashB64] = stored.split('$');
  if (algo !== 'scrypt' || !n || !saltB64 || !hashB64) return false;
  const expected = Buffer.from(hashB64, 'base64');
  const actual = await scrypt(password, Buffer.from(saltB64, 'base64'), expected.length, { ...PARAMS, N: Number(n) });
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

/** Device ids are stored only as a keyed hash. */
export function hashDeviceId(deviceId: string, pepper: string): string {
  return createHash('sha256').update(`${pepper}:${deviceId}`).digest('hex');
}
