// `delete-account` Edge Function: permanently deletes the caller's account. Storage objects are removed
// explicitly; deleting the auth user cascades to every table (all rows reference auth.users on delete cascade).
import { createClient } from "npm:@supabase/supabase-js@2.75.0";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});
const BUCKET = "book-files";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

async function listAll(prefix: string): Promise<string[]> {
  const paths: string[] = [];
  for (let offset = 0; ; offset += 1000) {
    const { data, error } = await admin.storage.from(BUCKET).list(prefix, { limit: 1000, offset });
    if (error) throw error;
    for (const entry of data ?? []) {
      const path = `${prefix}/${entry.name}`;
      // Folders have no id; recurse into them (<user>/<book>/<file>).
      if (entry.id === null) paths.push(...await listAll(path));
      else paths.push(path);
    }
    if (!data || data.length < 1000) break;
  }
  return paths;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });
  const token = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return json(401, { error: "unauthorized" });
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) return json(401, { error: "unauthorized" });
  const userId = data.user.id;

  try {
    const files = await listAll(userId);
    for (let i = 0; i < files.length; i += 100) {
      const { error: removeError } = await admin.storage.from(BUCKET).remove(files.slice(i, i + 100));
      if (removeError) throw removeError;
    }
    const { error: deleteError } = await admin.auth.admin.deleteUser(userId);
    if (deleteError) throw deleteError;
    return json(200, { deleted: true, files: files.length });
  } catch (e) {
    console.error("delete-account failed", e);
    return json(500, { error: "delete_failed" });
  }
});
