import assert from "node:assert/strict";
import { HttpError } from "../../functions/_shared/core.ts";
import { parseAudiusSearchResponse } from "../../functions/_shared/catalog.ts";

const audiusTrack = (overrides: Record<string, unknown> = {}) => ({
  id: "real-provider-id-1", title: "Example Song", user: { name: "Example Artist" },
  duration: 123, permalink: "/artist/example-song", is_streamable: true,
  ...overrides,
});

Deno.test("CAT-01: converts valid Audius metadata into normalized catalog candidate", () => {
  assert.deepEqual(parseAudiusSearchResponse({ data: [audiusTrack()] }), [{
    id: "real-provider-id-1", title: "Example Song", artist: "Example Artist",
    durationMs: 123000, permalink: "https://audius.co/artist/example-song",
  }]);
});

Deno.test("CAT-02: accepts a valid empty provider search response", () => {
  assert.deepEqual(parseAudiusSearchResponse({ data: [] }), []);
});

Deno.test("CAT-03: malformed provider envelope is not confused with zero results", () => {
  for (const bad of [null, {}, { data: null }, { data: "not-an-array" }, []]) {
    assert.throws(() => parseAudiusSearchResponse(bad), (e: unknown) =>
      e instanceof HttpError && e.status === 502 && e.code === "PROVIDER_INVALID_RESPONSE");
  }
});

Deno.test("CAT-04: skips unlisted, deleted or unavailable Audius records", () => {
  const result = parseAudiusSearchResponse({ data: [
    audiusTrack({ is_unlisted: true }),
    audiusTrack({ is_delete: true }),
    audiusTrack({ is_streamable: false, is_downloadable: false }),
  ] });
  assert.deepEqual(result, []);
});

Deno.test("CAT-05: rejects unsafe permalinks and invalid durations", () => {
  assert.deepEqual(parseAudiusSearchResponse({ data: [
    audiusTrack({ permalink: "https://malicious.example/song" }),
    audiusTrack({ duration: -5 }),
    audiusTrack({ duration: 90000 }),
  ] }), []);
});
