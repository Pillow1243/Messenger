import { createClient } from "jsr:@supabase/supabase-js@2";
import { consumeRateLimit } from "../_shared/rate_limit.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FORBIDDEN_FIELDS = ["plaintext", "body", "text", "preview", "title", "key", "mediaKey", "iv"];

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

let cachedAccessToken: { token: string; expiresAtMs: number } | null = null;

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function base64UrlEncodeBytes(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeJson(obj: unknown): string {
  return base64UrlEncodeBytes(new TextEncoder().encode(JSON.stringify(obj)));
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const clean = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(clean);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

async function getFcmAccessToken(sa: ServiceAccount): Promise<string> {
  const nowMs = Date.now();
  if (cachedAccessToken && cachedAccessToken.expiresAtMs - 60_000 > nowMs) {
    return cachedAccessToken.token;
  }
  const nowSec = Math.floor(nowMs / 1000);
  const tokenUri = sa.token_uri || "https://oauth2.googleapis.com/token";
  const header = { alg: "RS256", typ: "JWT" };
  const claimSet = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: tokenUri,
    iat: nowSec,
    exp: nowSec + 3600,
  };
  const unsigned = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(claimSet)}`;
  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(unsigned),
  );
  const assertion = `${unsigned}.${base64UrlEncodeBytes(new Uint8Array(sig))}`;

  const resp = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!resp.ok) {
    throw new Error(`fcm_oauth_failed_${resp.status}`);
  }
  const data = await resp.json();
  const token = String(data.access_token || "");
  const expiresIn = Number(data.expires_in || 3600);
  cachedAccessToken = { token, expiresAtMs: nowMs + expiresIn * 1000 };
  return token;
}

function isWithinQuietHours(nowHourUtc: number, start: number | null, end: number | null): boolean {
  if (start === null || end === null || start === end) return false;
  if (start < end) return nowHourUtc >= start && nowHourUtc < end;
  return nowHourUtc >= start || nowHourUtc < end;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
  if (req.method !== "POST") return json(405, { error: "method_not_allowed" });

  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) return json(401, { error: "missing_bearer_token" });

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authHeader } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: userErr } = await userClient.auth.getUser();
  if (userErr || !userData?.user) return json(401, { error: "invalid_token" });
  const callerId = userData.user.id;

  let payload: Record<string, unknown>;
  try {
    payload = await req.json();
  } catch {
    return json(400, { error: "invalid_json" });
  }

  // Zero-plaintext guard: reject any attempt to transit message content or keys via push.
  for (const forbidden of FORBIDDEN_FIELDS) {
    if (forbidden in payload && payload[forbidden] !== undefined && payload[forbidden] !== null) {
      return json(400, { error: "plaintext_forbidden_in_push", field: forbidden });
    }
  }

  const eventKind = String(payload.eventKind || "");
  const conversationId = String(payload.conversationId || "");
  const targetId = String(payload.messageId || payload.callId || "");

  if (!["message", "call"].includes(eventKind)) return json(400, { error: "invalid_event_kind" });
  if (!UUID_RE.test(conversationId) || !UUID_RE.test(targetId)) {
    return json(400, { error: "invalid_uuid" });
  }

  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const now = new Date();

  // Per-caller rate limiting (max 90 dispatches per 60 seconds), consumed atomically by
  // mm_consume_rate_limit_as(). Fails closed if the limiter is unreachable.
  const limit = await consumeRateLimit(admin, callerId, "push_dispatch", 90, 60);
  if (!limit.allowed) {
    if (limit.error === "rate_limited") {
      return json(429, { error: "rate_limited", bucket: "push_dispatch" });
    }
    return json(503, { error: "rate_limiter_unavailable" });
  }

  // Verify caller membership & ownership of the referenced message/call.
  const { data: conv } = await admin
    .from("mm_conversations")
    .select("id, type, deleted_at")
    .eq("id", conversationId)
    .maybeSingle();
  if (!conv || conv.deleted_at) return json(404, { error: "conversation_not_found" });

  const { data: callerMember } = await admin
    .from("mm_conversation_members")
    .select("role")
    .eq("conversation_id", conversationId)
    .eq("user_id", callerId)
    .maybeSingle();
  if (!callerMember) return json(403, { error: "not_a_member" });

  if (eventKind === "message") {
    const { data: msg } = await admin
      .from("mm_messages")
      .select("id, sender_id, created_at, deleted_at")
      .eq("id", targetId)
      .eq("conversation_id", conversationId)
      .maybeSingle();
    if (!msg || msg.deleted_at || msg.sender_id !== callerId) {
      return json(403, { error: "message_not_owned_by_caller" });
    }
    if (now.getTime() - new Date(msg.created_at).getTime() > 10 * 60_000) {
      return json(410, { error: "wakeup_expired" });
    }
  } else {
    const { data: call } = await admin
      .from("mm_calls")
      .select("id, initiator_id, state, started_at")
      .eq("id", targetId)
      .eq("conversation_id", conversationId)
      .maybeSingle();
    if (!call || call.initiator_id !== callerId) {
      return json(403, { error: "call_not_owned_by_caller" });
    }
    if (call.state !== "ringing" || now.getTime() - new Date(call.started_at).getTime() > 65_000) {
      return json(410, { error: "call_ring_expired" });
    }
  }

  // Opportunistic expiration cleanup.
  await admin.from("mm_push_log").delete().lt("expires_at", now.toISOString());

  // Fetch conversation recipients excluding sender & muted members.
  const { data: members } = await admin
    .from("mm_conversation_members")
    .select("user_id, muted_until, notification_level")
    .eq("conversation_id", conversationId)
    .neq("user_id", callerId);

  const candidateIds = (members || [])
    .filter((m) => {
      if (m.notification_level === "none") return false;
      if (m.muted_until && new Date(m.muted_until).getTime() > now.getTime()) return false;
      return true;
    })
    .map((m) => m.user_id as string);

  if (candidateIds.length === 0) {
    return json(200, { ok: true, delivered: 0, skipped: 0, reason: "no_recipients" });
  }

  // Exclude blocked relationships.
  const { data: blocks } = await admin
    .from("mm_blocks")
    .select("blocker_id, blocked_id")
    .or(`blocker_id.eq.${callerId},blocked_id.eq.${callerId}`);
  const blockedUsers = new Set<string>();
  for (const b of blocks || []) {
    if (b.blocker_id === callerId) blockedUsers.add(b.blocked_id);
    if (b.blocked_id === callerId) blockedUsers.add(b.blocker_id);
  }

  // Respect recipient notification preferences.
  const { data: prefs } = await admin
    .from("mm_notification_prefs")
    .select("*")
    .in("user_id", candidateIds);
  const prefMap = new Map<string, Record<string, unknown>>();
  for (const p of prefs || []) prefMap.set(p.user_id, p);

  const nowHourUtc = now.getUTCHours();
  const eligibleUserIds = candidateIds.filter((uid) => {
    if (blockedUsers.has(uid)) return false;
    const pref = prefMap.get(uid);
    if (!pref) return conv.type !== "channel" || eventKind === "call";
    if (pref.enabled === false) return false;
    if (
      eventKind !== "call" &&
      isWithinQuietHours(
        nowHourUtc,
        pref.quiet_hours_start as number | null,
        pref.quiet_hours_end as number | null,
      )
    ) {
      return false;
    }
    if (eventKind === "call") return pref.calls_enabled !== false;
    if (conv.type === "direct") return pref.direct_enabled !== false;
    if (conv.type === "group") return pref.group_enabled !== false;
    if (conv.type === "channel") return pref.channel_enabled === true;
    return true;
  });

  if (eligibleUserIds.length === 0) {
    return json(200, { ok: true, delivered: 0, skipped: candidateIds.length, reason: "filtered_by_prefs" });
  }

  // Record zero-plaintext notification rows for Realtime/foreground catchup (messages; calls already insert one in mm_create_call).
  if (eventKind === "message") {
    const notifRows = eligibleUserIds.map((uid) => ({
      user_id: uid,
      kind: "message",
      conversation_id: conversationId,
      message_id: targetId,
    }));
    await admin.from("mm_notifications").insert(notifRows);
  }

  // Fetch active tokens on non-revoked devices.
  const { data: tokens } = await admin
    .from("mm_push_tokens")
    .select("id, user_id, device_id, provider, token, mm_devices!inner(id, revoked_at)")
    .in("user_id", eligibleUserIds)
    .is("revoked_at", null)
    .gt("expires_at", now.toISOString());

  const activeTokens = (tokens || []).filter((t: Record<string, unknown>) => {
    const dev = t.mm_devices as { revoked_at: string | null } | null;
    return dev && dev.revoked_at === null && t.provider === "fcm";
  });

  const fcmRaw = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
  if (!fcmRaw) {
    return json(200, {
      ok: true,
      configured: false,
      reason: "fcm_credentials_missing",
      eligibleDevices: activeTokens.length,
      queuedNotifications: eligibleUserIds.length,
    });
  }

  let serviceAccount: ServiceAccount;
  try {
    serviceAccount = JSON.parse(fcmRaw);
    if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key) {
      throw new Error("incomplete_service_account");
    }
  } catch {
    return json(500, { error: "invalid_fcm_service_account_secret" });
  }

  let accessToken: string;
  try {
    accessToken = await getFcmAccessToken(serviceAccount);
  } catch (e) {
    return json(502, { error: e instanceof Error ? e.message : "fcm_auth_error" });
  }

  const ttlSeconds = eventKind === "call" ? 60 : 86_400;
  const expiresAtMs = now.getTime() + ttlSeconds * 1000;
  let delivered = 0;
  let deduplicated = 0;
  let revokedStale = 0;

  for (const row of activeTokens) {
    const dedupKey = `${eventKind}:${targetId}:${row.device_id}`;
    const { error: dedupErr } = await admin.from("mm_push_log").insert({
      dedup_key: dedupKey,
      user_id: row.user_id,
      kind: eventKind,
      expires_at: new Date(expiresAtMs).toISOString(),
    });
    if (dedupErr) {
      deduplicated++;
      continue;
    }

    const fcmResp = await fetch(
      `https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: row.token,
            data: {
              type: "mm_wakeup",
              v: "2",
              event_kind: eventKind,
              conv_type: String(conv.type),
              conversation_id: conversationId,
              target_id: targetId,
              sent_at_ms: String(now.getTime()),
              expires_at_ms: String(expiresAtMs),
            },
            android: {
              priority: "HIGH",
              ttl: `${ttlSeconds}s`,
              collapse_key: eventKind === "call" ? `mm_call_${targetId}` : `mm_conv_${conversationId}`,
            },
          },
        }),
      },
    );

    if (fcmResp.ok) {
      delivered++;
    } else {
      const errBody = await fcmResp.text();
      if (fcmResp.status === 404 || fcmResp.status === 410 || errBody.includes("UNREGISTERED")) {
        await admin
          .from("mm_push_tokens")
          .update({ revoked_at: now.toISOString(), updated_at: now.toISOString() })
          .eq("id", row.id);
        revokedStale++;
      }
    }
  }

  return json(200, {
    ok: true,
    configured: true,
    delivered,
    deduplicated,
    revokedStale,
  });
});
