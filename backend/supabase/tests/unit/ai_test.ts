import assert from "node:assert/strict";
import { HttpError } from "../../functions/_shared/core.ts";
import type { Track } from "../../functions/_shared/catalog.ts";
import { candidateQueries, firstMatchingCandidates } from "../../functions/_shared/ai_query.ts";
import { validateGenerated } from "../../functions/v1-generate-playlist/index.ts";

const ID_1 = "123e4567-e89b-42d3-a456-426614174000";
const ID_2 = "123e4567-e89b-42d3-a456-426614174001";
const UNKNOWN = "123e4567-e89b-42d3-a456-426614174099";
const tracks: Track[] = [
  { id: ID_1, title: "One", artist: "A", durationMs: 120000, availability: "EXTERNAL_ONLY" },
  { id: ID_2, title: "Two", artist: "B", durationMs: 90000, availability: "IN_APP" },
];

Deno.test("AI-01: retains original query and adds bounded music-specific alternatives", () => {
  const queries = candidateQueries("Хочу спокойную музыку для сна");
  assert.equal(queries[0], "Хочу спокойную музыку для сна");
  assert.equal(queries.includes("calm"), true);
  assert.equal(queries.includes("sleep"), true);
  assert.equal(queries.length <= 4, true);
});

Deno.test("AI-02: explicit filters are searched before generic fallback terms", () => {
  const queries = candidateQueries("Please make calm music for sleep", { genre: "jazz", mood: "ambient" });
  assert.deepEqual(queries.slice(0, 3), ["Please make calm music for sleep", "jazz", "ambient"]);
});

Deno.test("AI-03: catalog search stops at the first verified candidate batch", async () => {
  const called: string[] = [];
  const result = await firstMatchingCandidates(["empty", "found", "never"], async q => {
    called.push(q);
    return q === "found" ? tracks : [];
  });
  assert.deepEqual(result, tracks);
  assert.deepEqual(called, ["empty", "found"]);
});

Deno.test("AI-04: provider errors propagate instead of being hidden by fallback", async () => {
  let calls = 0;
  await assert.rejects(firstMatchingCandidates(["fail", "not-called"], async () => {
    calls++;
    throw new Error("provider error");
  }), /provider error/);
  assert.equal(calls, 1);
});

Deno.test("AI-05: approved AI IDs retain order and get sequential positions", () => {
  const result = validateGenerated({ title: " Chill ", description: " Verified ", items: [
    { trackId: ID_2, reason: "second" }, { trackId: ID_1, reason: "first" },
  ] }, tracks, 2);
  assert.deepEqual(result.items, [
    { trackId: ID_2, position: 1, reason: "second" },
    { trackId: ID_1, position: 2, reason: "first" },
  ]);
  assert.equal(result.title, "Chill");
  assert.deepEqual(result.warnings, []);
});

Deno.test("AI-06: hallucinated IDs and duplicates never enter playlist preview", () => {
  const result = validateGenerated({ title: "Demo", description: "", items: [
    { trackId: UNKNOWN }, { trackId: ID_1 }, { trackId: ID_1 }, { trackId: ID_2 },
  ] }, tracks, 2);
  assert.deepEqual(result.items.map(x => x.trackId), [ID_1, ID_2]);
});

Deno.test("AI-07: no known ID is a provider error, not an empty fake playlist", () => {
  assert.throws(() => validateGenerated({ title: "Demo", description: "", items: [
    { trackId: UNKNOWN },
  ] }, tracks, 2), (e: unknown) => e instanceof HttpError && e.status === 502 && e.code === "AI_INVALID_RESULT");
});

Deno.test("AI-08: a short verified list adds an explicit warning", () => {
  const result = validateGenerated({ title: "Demo", description: "", items: [
    { trackId: ID_1 },
  ] }, tracks, 2);
  assert.equal(result.items.length, 1);
  assert.equal(result.warnings.length, 1);
});

Deno.test("AI-09: corrupt AI shapes and too-long titles are rejected", () => {
  for (const invalid of [null, {}, { title: "", description: "", items: [] },
    { title: "x".repeat(151), description: "", items: [] },
    { title: "Valid", description: "", items: "not-array" }]) {
    assert.throws(() => validateGenerated(invalid, tracks, 2), HttpError);
  }
});

Deno.test("AI-10: AI explanations are safely bounded to 250 characters", () => {
  const result = validateGenerated({ title: "Demo", description: "", items: [
    { trackId: ID_1, reason: "x".repeat(1000) },
  ] }, tracks, 1);
  assert.equal(result.items[0].reason?.length, 250);
});
