import { createClient } from "jsr:@supabase/supabase-js@2";
import { consumeRateLimit } from "../_shared/rate_limit.ts";

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

  // Deletion is destructive and rate limited (3 attempts per hour per account) so a stolen
  // session cannot be used to hammer the cleanup path. Fails closed.
  const limit = await consumeRateLimit(admin, uid, "account_delete", 3, 3600);
  if (!limit.allowed) {
    if (limit.error === "rate_limited") {
      return json(429, { error: "rate_limited" });
    }
    return json(503, { error: "rate_limiter_unavailable" });
  }

  // 1. Delete the encrypted media blobs this user uploaded to mm-media.
  //
  // The list comes from storage itself (objects owned by the account), not from mm_attachments:
  // an attachment row can be deleted by its uploader while the blob stays behind, and such a
  // blob would otherwise outlive the account with nobody left who is allowed to remove it.
  // Nothing is deleted from the database until storage has been emptied: if any step fails the
  // request is refused and can simply be repeated.
  const { data: owned, error: ownedErr } = await admin.rpc("mm_media_objects_of", { p_user: uid });
  if (ownedErr) {
    console.error("mm-account-delete: media listing failed", ownedErr.code ?? "unknown");
    return json(500, { error: "media_lookup_failed" });
  }
  const mediaPaths = ((owned ?? []) as { object_path: string }[])
    .map((o) => o.object_path)
    .filter((path) => typeof path === "string" && path.length > 0);
  for (let i = 0; i < mediaPaths.length; i += 100) {
    const { error: removeErr } = await admin.storage
      .from("mm-media")
      .remove(mediaPaths.slice(i, i + 100));
    if (removeErr) {
      console.error("mm-account-delete: media removal failed", removeErr.name ?? "unknown");
      return json(500, { error: "media_delete_failed" });
    }
  }
  const { error: attachmentsErr } = await admin.from("mm_attachments").delete().eq("uploader_id", uid);
  if (attachmentsErr) {
    console.error("mm-account-delete: attachment rows not removed", attachmentsErr.code ?? "unknown");
    return json(500, { error: "media_delete_failed" });
  }

  // 2. Delete user avatars in mm-avatars/<uid>/.
  const { data: avatarFiles, error: avatarListErr } = await admin.storage
    .from("mm-avatars")
    .list(uid, { limit: 1000 });
  if (avatarListErr) {
    console.error("mm-account-delete: avatar listing failed", avatarListErr.name ?? "unknown");
    return json(500, { error: "media_lookup_failed" });
  }
  if (avatarFiles && avatarFiles.length > 0) {
    const { error: avatarErr } = await admin.storage
      .from("mm-avatars")
      .remove(avatarFiles.map((f) => `${uid}/${f.name}`));
    if (avatarErr) {
      console.error("mm-account-delete: avatar removal failed", avatarErr.name ?? "unknown");
      return json(500, { error: "media_delete_failed" });
    }
  }

  // 3. Run database-level key rotation, revocation & anonymization RPC as the user.
  const { error: rpcErr } = await userClient.rpc("mm_delete_me");
  if (rpcErr) {
    // The database message stays server-side: it can contain row identifiers, constraint names
    // and policy text, which an unauthenticated-ish caller has no reason to read.
    console.error("mm_delete_me failed", rpcErr.code ?? "unknown");
    return json(500, { error: "delete_rpc_failed" });
  }

  // 4. Permanently remove auth.users record so email/credentials can be cleanly forgotten.
  const { error: authDelErr } = await admin.auth.admin.deleteUser(uid);
  if (authDelErr) {
    console.error("auth admin deleteUser failed", authDelErr.status ?? "unknown");
    return json(500, { error: "auth_delete_failed" });
  }

  return json(200, { ok: true, deletedUserId: uid });
});
