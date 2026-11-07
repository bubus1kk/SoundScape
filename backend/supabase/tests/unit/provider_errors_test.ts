import assert from "node:assert/strict";
import { networkCategory, networkFailure, providerHttpFailure, invalidProviderResponse } from "../../functions/_shared/provider_errors.ts";

Deno.test("UPSTREAM-01: classifies timeout, DNS and TLS failures", () => {
  assert.equal(networkCategory(new Error("request timed out")), "TIMEOUT");
  assert.equal(networkCategory(new Error("getaddrinfo ENOTFOUND")), "DNS");
  assert.equal(networkCategory(new Error("certificate verify failed")), "TLS");
  assert.equal(networkCategory(new Error("connection reset")), "NETWORK");
});

Deno.test("UPSTREAM-02: strips internal networking details from client-facing errors", () => {
  const error = networkFailure("AUDIUS", "search", new Error("CERTIFICATE ERROR secret=abc"));
  assert.equal(error.status, 503);
  assert.equal(error.code, "PROVIDER_UNAVAILABLE");
  assert.equal(error.message.includes("secret=abc"), false);
});

Deno.test("UPSTREAM-03: GigaChat OAuth 401 is not a user authentication 401", () => {
  const error = providerHttpFailure("GIGACHAT", "oauth", 401);
  assert.equal(error.status, 502);
  assert.equal(error.code, "AI_OAUTH_REJECTED");
});

Deno.test("UPSTREAM-04: provider 429 becomes bounded client retry-after", () => {
  const error = providerHttpFailure("AUDIUS", "search", 429, "45");
  assert.equal(error.status, 429);
  assert.equal(error.code, "RATE_LIMITED");
  assert.equal(error.retryAfter, 45);
});

Deno.test("UPSTREAM-05: malformed external provider JSON is a safe 502 error", () => {
  const error = invalidProviderResponse("GIGACHAT", "generation");
  assert.equal(error.status, 502);
  assert.equal(error.code, "AI_INVALID_RESULT");
});
