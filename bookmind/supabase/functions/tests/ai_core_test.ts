import { assertEquals, assert } from "jsr:@std/assert@1.0.13";
import Anthropic from "npm:@anthropic-ai/sdk@0.135.0";
import { costMicros, type Deps, handleAi, mapError, SERVER_GUARD, validate } from "../_shared/ai_core.ts";

const body = { task: "SUMMARY", promptVersion: "summary/3", system: "sys", user: "<book_text>x</book_text>", maxOutputTokens: 2000 };

function message(overrides: Partial<Anthropic.Beta.Messages.BetaMessage> = {}): Anthropic.Beta.Messages.BetaMessage {
  return {
    id: "msg_1",
    type: "message",
    role: "assistant",
    model: "claude-opus-5-5",
    content: [{ type: "text", text: '{"ok":true}', citations: null }],
    stop_reason: "end_turn",
    stop_sequence: null,
    usage: { input_tokens: 1000, output_tokens: 200 },
    ...overrides,
  } as unknown as Anthropic.Beta.Messages.BetaMessage;
}

function deps(over: Partial<Deps> = {}) {
  const ledger: Array<Record<string, unknown>> = [];
  const calls: Array<Record<string, unknown>> = [];
  const d: Deps = {
    create: (params) => { calls.push(params as unknown as Record<string, unknown>); return Promise.resolve(message()); },
    model: "claude-opus-5-5",
    reserve: () => Promise.resolve(7),
    finish: (id, entry) => { ledger.push({ id, ...entry }); return Promise.resolve(); },
    now: () => new Date("2026-10-18T15:00:00Z"),
    ...over,
  };
  return { d, ledger, calls };
}

Deno.test("valid request is proxied with guard, effort and fallbacks, and usage is priced", async () => {
  const { d, ledger, calls } = deps();
  const r = await handleAi(d, "user-1", body);
  assertEquals(r.status, 200);
  assertEquals(r.body.text, '{"ok":true}');
  assertEquals(r.body.usage, { inputTokens: 1000, outputTokens: 200, costMicros: 8000 });
  const sent = calls[0];
  assert(String(sent.system).startsWith(SERVER_GUARD));
  assertEquals(sent.model, "claude-opus-5-5");
  assertEquals(sent.fallbacks, "default");
  assertEquals(sent.betas, ["server-side-fallback-2026-07-01"]);
  assertEquals((sent.output_config as { effort: string }).effort, "medium");
  assertEquals("temperature" in sent, false);
  assertEquals("thinking" in sent, false);
  assertEquals(ledger[0], { id: 7, status: "ok", model: "claude-opus-5-5", inputTokens: 1000, outputTokens: 200, costMicros: 8000 });
});

Deno.test("conversational tasks use low effort", async () => {
  const { d, calls } = deps();
  await handleAi(d, "u", { ...body, task: "TEACH" });
  assertEquals((calls[0].output_config as { effort: string }).effort, "low");
});

Deno.test("validation rejects unknown tasks, oversize prompts and bad limits", () => {
  assertEquals(validate({ ...body, task: "CHAT" }).ok, false);
  const big = validate({ ...body, user: "x".repeat(130_000) });
  assert(!big.ok && big.reply.status === 413);
  assertEquals(validate({ ...body, maxOutputTokens: 100_000 }).ok, false);
  assertEquals(validate({ ...body, user: "  " }).ok, false);
  assertEquals(validate(null).ok, false);
});

Deno.test("quota exhaustion returns 429 with the next UTC midnight", async () => {
  const { d, calls } = deps({ reserve: () => Promise.resolve(null) });
  const r = await handleAi(d, "u", body);
  assertEquals(r.status, 429);
  assertEquals(r.body.resetAt, Date.UTC(2026, 9, 19));
  assertEquals(calls.length, 0);
});

Deno.test("a refusal from the whole fallback chain is reported, not retried", async () => {
  const { d } = deps({
    create: () => Promise.resolve(message({ stop_reason: "refusal", content: [], ...({ stop_details: { type: "refusal", category: "cyber" } } as object) })),
  });
  const r = await handleAi(d, "u", body);
  assertEquals(r.status, 422);
  assertEquals(r.body, { error: "refusal", category: "cyber" });
});

Deno.test("a fallback model is priced by the model that actually answered", async () => {
  const { d } = deps({ create: () => Promise.resolve(message({ model: "claude-opus-4-8" })) });
  const r = await handleAi(d, "u", body);
  assertEquals((r.body.usage as { costMicros: number }).costMicros, 1000 * 5 + 200 * 25);
});

Deno.test("SDK errors map to retryable or terminal statuses and the ledger records the failure", async () => {
  const { d, ledger } = deps({ create: () => Promise.reject(new Anthropic.APIConnectionTimeoutError()) });
  const r = await handleAi(d, "u", body);
  assertEquals(r.status, 504);
  assertEquals(ledger[0].status, "error");
  assertEquals(mapError(new Anthropic.APIConnectionError({ message: "down" })).status, 502);
  assertEquals(mapError(new Error("boom")).status, 500);
});

Deno.test("cost table", () => {
  assertEquals(costMicros("claude-opus-5-5", 1_000_000, 0), 4_000_000);
  assertEquals(costMicros("unknown", 1, 1), null);
});
