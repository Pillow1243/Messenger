import { createClient } from "jsr:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) return json(401, { error: "missing_bearer_token" });

  let body: { confirm?: string } = {};
  try {
    body = await req.json();
  } catch {
    return json(400, { error: "invalid_json" });
  }
  if (body.confirm !== "DELETE") {
    return json(400, { error: "confirmation_required" });
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userErr } = await userClient.auth.getUser();
  if (userErr || !userData?.user) return json(401, { error: "invalid_token" });
  const uid = userData.user.id;

  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  // 1. Delete encrypted media blobs uploaded by this user in mm-media.
  const { data: attachments } = await admin
    .from("mm_attachments")
    .select("id, bucket, object_path")
    .eq("uploader_id", uid);
  if (attachments && attachments.length > 0) {
    const mediaPaths = attachments
      .filter((a) => a.bucket === "mm-media" && a.object_path)
      .map((a) => a.object_path as string);
    if (mediaPaths.length > 0) {
      await admin.storage.from("mm-media").remove(mediaPaths);
    }
    await admin.from("mm_attachments").delete().eq("uploader_id", uid);
  }

  // 2. Delete user avatars in mm-avatars/<uid>/.
  const { data: avatarFiles } = await admin.storage.from("mm-avatars").list(uid, { limit: 100 });
  if (avatarFiles && avatarFiles.length > 0) {
    await admin.storage
      .from("mm-avatars")
      .remove(avatarFiles.map((f) => `${uid}/${f.name}`));
  }

  // 3. Run database-level key rotation, revocation & anonymization RPC as the user.
  const { error: rpcErr } = await userClient.rpc("mm_delete_me");
  if (rpcErr) {
    return json(500, { error: "delete_rpc_failed", detail: rpcErr.message });
  }

  // 4. Permanently remove auth.users record so email/credentials can be cleanly forgotten.
  const { error: authDelErr } = await admin.auth.admin.deleteUser(uid);
  if (authDelErr) {
    return json(500, { error: "auth_delete_failed", detail: authDelErr.message });
  }

  return json(200, { ok: true, deletedUserId: uid });
});
