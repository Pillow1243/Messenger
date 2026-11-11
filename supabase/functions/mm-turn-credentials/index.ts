import { createClient } from "jsr:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const STUN_FALLBACK = [
  { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] },
];

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

async function hmacSha1Base64(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message));
  const bytes = new Uint8Array(sig);
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
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

  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  // Rate limit: max 30 credential mints per 5 minutes per authenticated user.
  const now = new Date();
  const { data: rlRow } = await admin
    .from("mm_rate_limits")
    .select("window_start, count")
    .eq("user_id", callerId)
    .eq("bucket", "turn_creds")
    .maybeSingle();
  if (rlRow) {
    const ageMs = now.getTime() - new Date(rlRow.window_start).getTime();
    if (ageMs < 300_000 && rlRow.count >= 30) {
      return json(429, { error: "rate_limited" });
    }
    await admin.from("mm_rate_limits").upsert({
      user_id: callerId,
      bucket: "turn_creds",
      window_start: ageMs < 300_000 ? rlRow.window_start : now.toISOString(),
      count: ageMs < 300_000 ? rlRow.count + 1 : 1,
    });
  } else {
    await admin.from("mm_rate_limits").insert({
      user_id: callerId,
      bucket: "turn_creds",
      window_start: now.toISOString(),
      count: 1,
    });
  }

  // Option 1: Cloudflare Realtime TURN short-lived credentials.
  const cfTokenId = Deno.env.get("CF_TURN_TOKEN_ID");
  const cfApiToken = Deno.env.get("CF_TURN_API_TOKEN");
  if (cfTokenId && cfApiToken) {
    try {
      const resp = await fetch(
        `https://rtc.live.cloudflare.com/v1/turn/keys/${cfTokenId}/credentials/generate`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${cfApiToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ ttl: 3600 }),
        },
      );
      if (resp.ok) {
        const data = await resp.json();
        const servers = Array.isArray(data.iceServers) ? data.iceServers : [data.iceServers];
        return json(200, {
          iceServers: [...STUN_FALLBACK, ...servers.filter(Boolean)],
          ttl: 3600,
          turnConfigured: true,
          provider: "cloudflare",
        });
      }
    } catch {
      // Fall through to secondary TURN providers or STUN fallback.
    }
  }

  // Option 2: Coturn ephemeral REST HMAC-SHA1 credentials (TURN_URLS + TURN_SHARED_SECRET).
  const turnUrlsRaw = Deno.env.get("TURN_URLS");
  const turnSharedSecret = Deno.env.get("TURN_SHARED_SECRET");
  if (turnUrlsRaw && turnSharedSecret) {
    const ttl = 3600;
    const expiry = Math.floor(now.getTime() / 1000) + ttl;
    const username = `${expiry}:${callerId}`;
    const credential = await hmacSha1Base64(turnSharedSecret, username);
    const urls = turnUrlsRaw.split(",").map((u) => u.trim()).filter(Boolean);
    return json(200, {
      iceServers: [...STUN_FALLBACK, { urls, username, credential }],
      ttl,
      turnConfigured: true,
      provider: "coturn_hmac",
    });
  }

  // Option 3: Metered TURN dynamic credentials.
  const meteredDomain = Deno.env.get("METERED_DOMAIN");
  const meteredKey = Deno.env.get("METERED_API_KEY");
  if (meteredDomain && meteredKey) {
    try {
      const resp = await fetch(
        `https://${meteredDomain}/api/v1/turn/credentials?apiKey=${encodeURIComponent(meteredKey)}`,
      );
      if (resp.ok) {
        const servers = await resp.json();
        if (Array.isArray(servers) && servers.length > 0) {
          return json(200, {
            iceServers: [...STUN_FALLBACK, ...servers],
            ttl: 3600,
            turnConfigured: true,
            provider: "metered",
          });
        }
      }
    } catch {
      // Fall through cleanly.
    }
  }

  // Option 4: Explicit server-side TURN_URLS + TURN_USERNAME + TURN_CREDENTIAL env vars.
  const turnUser = Deno.env.get("TURN_USERNAME");
  const turnCred = Deno.env.get("TURN_CREDENTIAL");
  if (turnUrlsRaw && turnUser && turnCred) {
    const urls = turnUrlsRaw.split(",").map((u) => u.trim()).filter(Boolean);
    return json(200, {
      iceServers: [...STUN_FALLBACK, { urls, username: turnUser, credential: turnCred }],
      ttl: 3600,
      turnConfigured: true,
      provider: "static_env",
    });
  }

  // Honest STUN-only fallback when TURN secrets have not yet been set on the project.
  return json(200, {
    iceServers: STUN_FALLBACK,
    ttl: 600,
    turnConfigured: false,
    provider: "stun_only",
  });
});
