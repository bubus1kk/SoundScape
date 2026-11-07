// SoundScape Practice 11 — pure unit tests for existing Edge shared helpers.
// Run from the backend directory: deno test --no-remote supabase/tests/unit/
import assert from "node:assert/strict";
import {
  HttpError, text, int, bool, uuid, ids, body, parseRpcSuccess,
  parseQuotaResult, isSafeExternalUrl, errorResponse,
} from "../../functions/_shared/core.ts";

const USER_ID = "123e4567-e89b-42d3-a456-426614174000";
const LEASE_ID = "123e4567-e89b-42d3-a456-426614174001";

Deno.test("CORE-01: trims a valid required text field", () => {
  assert.equal(text("  ambient  ", 20, "query"), "ambient");
});

Deno.test("CORE-02: rejects blank or overlong required text", () => {
  assert.throws(() => text("   ", 20, "query"), (e: unknown) => e instanceof HttpError && e.status === 400);
  assert.throws(() => text("a".repeat(21), 20, "query"), (e: unknown) => e instanceof HttpError && e.code === "VALIDATION_ERROR");
});

Deno.test("CORE-03: validates integer ranges and defaults", () => {
  assert.equal(int(undefined, 10, 1, 50, "count"), 10);
  assert.equal(int(50, 10, 1, 50, "count"), 50);
  assert.throws(() => int(51, 10, 1, 50, "count"), HttpError);
  assert.throws(() => int(1.5, 10, 1, 50, "count"), HttpError);
});

Deno.test("CORE-04: does not coerce untrusted boolean values", () => {
  assert.equal(bool(undefined), false);
  assert.equal(bool(true), true);
  assert.throws(() => bool("true"), HttpError);
});

Deno.test("CORE-05: accepts UUID and rejects malformed identifiers", () => {
  assert.equal(uuid(USER_ID), USER_ID);
  assert.throws(() => uuid("not-a-uuid"), (e: unknown) => e instanceof HttpError && e.status === 400);
});

Deno.test("CORE-06: rejects duplicate and invalid genre/mood IDs", () => {
  assert.deepEqual(ids([1, 2, 32767]), [1, 2, 32767]);
  assert.deepEqual(ids(undefined), []);
  assert.throws(() => ids([1, 1]), HttpError);
  assert.throws(() => ids([0]), HttpError);
  assert.throws(() => ids(Array.from({ length: 21 }, (_, i) => i + 1)), HttpError);
});

Deno.test("CORE-07: parses an object-shaped JSON POST body", async () => {
  const req = new Request("http://localhost/edge", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ query: "lofi", limit: 5 }),
  });
  assert.deepEqual(await body(req), { query: "lofi", limit: 5 });
});

Deno.test("CORE-08: rejects JSON arrays, malformed data and wrong content type", async () => {
  const mk = (content: string, ct = "application/json") => new Request("http://localhost/edge", {
    method: "POST", headers: { "content-type": ct }, body: content,
  });
  await assert.rejects(body(mk("[]")), HttpError);
  await assert.rejects(body(mk("{no")), HttpError);
  await assert.rejects(body(mk("{}", "text/plain")), HttpError);
});

Deno.test("CORE-09: rejects oversized payload before business logic", async () => {
  const req = new Request("http://localhost/edge", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ value: "x".repeat(12001) }),
  });
  await assert.rejects(body(req), (e: unknown) => e instanceof HttpError && e.status === 413);
});

Deno.test("CORE-10: quota allows generation only with a valid lease UUID", () => {
  assert.deepEqual(parseQuotaResult({ allowed: true, leaseId: LEASE_ID }, "GENERATE"), { allowed: true, leaseId: LEASE_ID });
  assert.throws(() => parseQuotaResult({ allowed: true }, "GENERATE"), HttpError);
  assert.deepEqual(parseQuotaResult({ allowed: true }, "SEARCH"), { allowed: true, leaseId: null });
});

Deno.test("CORE-11: rejected quotas preserve bounded retry information", () => {
  assert.deepEqual(parseQuotaResult({ allowed: false, reason: "USER_LIMIT", retryAfter: 45 }, "GENERATE"), {
    allowed: false, reason: "USER_LIMIT", retryAfter: 45,
  });
  assert.throws(() => parseQuotaResult({ allowed: false, reason: "UNKNOWN", retryAfter: 45 }, "GENERATE"), HttpError);
  assert.throws(() => parseQuotaResult({ allowed: false, reason: "USER_LIMIT", retryAfter: 90000 }, "GENERATE"), HttpError);
});

Deno.test("CORE-12: malformed quota responses fail closed", () => {
  for (const value of [null, [], {}, { allowed: "yes" }, { allowed: true, leaseId: "bad" }]) {
    assert.throws(() => parseQuotaResult(value, "GENERATE"), (e: unknown) => e instanceof HttpError && e.status === 503);
  }
});

Deno.test("CORE-13: decodes valid RPC JSON and empty 204 responses", async () => {
  assert.deepEqual(await parseRpcSuccess(new Response('{"ok":true}', { status: 200 })), { ok: true });
  assert.equal(await parseRpcSuccess(new Response(null, { status: 204 })), null);
});

Deno.test("CORE-14: malformed internal RPC response becomes safe upstream error", async () => {
  await assert.rejects(parseRpcSuccess(new Response("NOT_JSON", { status: 200 })),
    (e: unknown) => e instanceof HttpError && e.status === 502 && e.code === "DATABASE_RESPONSE_INVALID");
});

Deno.test("CORE-15: only permitted Audius https external links pass", () => {
  assert.equal(isSafeExternalUrl("https://audius.co/artist/track"), true);
  for (const unsafe of ["http://audius.co/artist/track", "https://evil.example/music", "https://audius.co.evil.example/a", "javascript:alert(1)"]) {
    assert.equal(isSafeExternalUrl(unsafe), false);
  }
});

Deno.test("CORE-16: generic exception responses never leak secrets", async () => {
  const secret = "NEVER_PRINT_THIS_SECRET";
  const res = errorResponse(new Error(secret));
  assert.equal(res.status, 500);
  const payload = await res.json();
  assert.equal(payload.error.code, "INTERNAL_ERROR");
  assert.equal(typeof payload.error.requestId, "string");
  assert.equal(JSON.stringify(payload).includes(secret), false);
});
