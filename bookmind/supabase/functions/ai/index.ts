// `ai` Edge Function: authenticated, quota-limited proxy from the BookMind app to Claude.
// Secrets (set with `supabase secrets set`): ANTHROPIC_API_KEY, optional AI_MODEL (default claude-opus-5-5),
// optional AI_DAILY_REQUEST_LIMIT (default 150). SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY are provided by Supabase.
import Anthropic from "npm:@anthropic-ai/sdk@0.135.0";
import { createClient } from "npm:@supabase/supabase-js@2.75.0";
import { handleAi, type Task } from "../_shared/ai_core.ts";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
// One automatic SDK retry for transient failures; the app retries on top of that with backoff.
const anthropic = new Anthropic({ apiKey: Deno.env.get("ANTHROPIC_API_KEY"), maxRetries: 1 });
const model = Deno.env.get("AI_MODEL") ?? "claude-opus-5-5";
const dailyLimit = Number(Deno.env.get("AI_DAILY_REQUEST_LIMIT") ?? "150");

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });
  const token = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return json(401, { error: "unauthorized" });
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) return json(401, { error: "unauthorized" });

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "invalid_json" });
  }

  const reply = await handleAi(
    {
      create: (params, options) => anthropic.beta.messages.create(params, options),
      model,
      reserve: async (userId: string, task: Task) => {
        const r = await admin.rpc("ai_reserve_request", { p_user: userId, p_task: task, p_daily_limit: dailyLimit });
        if (r.error) throw r.error;
        return r.data as number | null;
      },
      finish: async (id, entry) => {
        await admin.from("ai_requests").update({
          status: entry.status,
          model: entry.model ?? null,
          input_tokens: entry.inputTokens ?? null,
          output_tokens: entry.outputTokens ?? null,
          cost_micros: entry.costMicros ?? null,
        }).eq("id", id);
      },
      now: () => new Date(),
    },
    data.user.id,
    body,
  );
  return json(reply.status, reply.body);
});
