import type { ZodType } from 'zod';

export class HttpError extends Error {
  constructor(readonly statusCode: number, readonly code: string, message?: string) {
    super(message ?? code);
  }
}

export function parse<T>(schema: ZodType<T>, data: unknown): T {
  const r = schema.safeParse(data);
  if (!r.success) throw new HttpError(400, 'validation_error', r.error.issues.map((i) => `${i.path.join('.') || 'body'}: ${i.message}`).join('; '));
  return r.data;
}
