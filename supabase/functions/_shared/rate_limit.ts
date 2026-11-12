// Shared server-side rate limiting for the Messenger Edge Functions.
//
// Why this exists: the first implementation wrote directly into `public.mm_rate_limits` using
// guessed column names (`user_id`, `bucket`, `count`). The real table is
// `(subject, action, window_start, hits)`, so every insert and upsert failed and - because the
// errors were ignored - both `mm-push-dispatch` and `mm-turn-credentials` were effectively
// unthrottled. The counter now goes through `mm_consume_rate_limit_as()`, a SECURITY DEFINER
// function that is executable only by `service_role`, which keeps the limiter atomic (single
// INSERT ... ON CONFLICT ... RETURNING) and cannot be forged by a client.
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

export type RateLimitAction = "push_dispatch" | "turn_creds" | "account_delete";

export interface RateLimitDecision {
  allowed: boolean;
  /** Database error text when the check itself failed (fail-closed). */
  error?: string;
}

/**
 * Atomically consumes one slot of a per-subject budget.
 *
 * Fails **closed**: if the limiter cannot be reached the caller must reject the request rather
 * than silently allowing unlimited work (that is how the previous bug hid).
 */
export async function consumeRateLimit(
  admin: SupabaseClient,
  subject: string,
  action: RateLimitAction,
  maxPerWindow: number,
  windowSeconds: number,
): Promise<RateLimitDecision> {
  const { error } = await admin.rpc("mm_consume_rate_limit_as", {
    p_subject: subject,
    p_action: action,
    p_max: maxPerWindow,
    p_window_seconds: windowSeconds,
  });
  if (!error) return { allowed: true };
  const message = error.message ?? "rate_limit_error";
  const limited = message.includes("rate_limited");
  return { allowed: false, error: limited ? "rate_limited" : message };
}
