import assert from "node:assert/strict";
import { HttpError } from "../../functions/_shared/core.ts";
import { findGenerationCandidates, type GenerationSearch } from "../../functions/_shared/catalog.ts";
import { generatePreview } from "../../functions/v1-generate-playlist/index.ts";

const request: GenerationSearch = {
  prompt: "", genre: "", mood: "", genreIds: [], moodIds: [], onlyInApp: false,
  context: [], pinnedTrackIds: [],
};
const verified = {
  provider: "APPLE_MUSIC", external_url: "https://music.apple.com/us/song/example/42",
  verified_at: "2026-01-01", can_stream: false,
};
const track = (number: number, title: string) => ({
  id: `123e4567-e89b-42d3-a456-${String(number).padStart(12, "0")}`,
  title, duration_ms: 90_000, track_artists: [{ credit_order: 1, artists: { name: "Test Artist" } }],
  track_sources: [verified], track_genres: [], track_moods: [], playlist_tracks: [],
});
const editorial = (title: string, description: string, position = 1, genres: string[] = []) => ({
  position, playlists: {
    title, description, is_editorial: true, visibility: "PUBLIC", published_at: "2026-01-01",
    playlist_genres: genres.map(name => ({ genres: { name } })),
  },
});
const night = {
  ...track(1, "Black Water"),
  playlist_tracks: [editorial("Берлин: контуры ночного сигнала", "Проявляется ночной городской маршрут.")],
};
const calm = {
  ...track(2, "Quiet Shore"),
  track_moods: [{ mood_id: 4, moods: { name: "Спокойное" } }],
  track_genres: [{ genre_id: 2, genres: { name: "Ambient" } }],
};
const work = track(3, "Music Lofi Beats");
const paris = {
  ...track(4, "La lune"),
  playlist_tracks: [editorial("Париж: мягкий свет", "Вечерняя прогулка.")],
};
const unrelated = track(5, "Unrelated");
const rows = [night, calm, work, paris, unrelated];
type Mode = Parameters<typeof generatePreview>[0];

async function preview(mode: Mode, options: Partial<GenerationSearch>, expected: string[], catalog: unknown[] = rows) {
  const search: typeof findGenerationCandidates = (query, count, ctx) =>
    findGenerationCandidates(query, count, ctx, async (table, queryString) => {
      assert.equal(table, "tracks");
      assert.match(queryString, /track_genres\(genre_id,genres\(name\)\)/);
      return catalog;
    });
  return await generatePreview(mode, { ...request, ...options }, 1, undefined, search, async (prompt, candidates) => {
    assert.ok(prompt.startsWith(mode));
    for (const context of options.context ?? []) assert.ok(prompt.includes(context.title));
    assert.deepEqual(candidates.map(track => track.id), expected);
    return { title: "Подборка", description: "Тестовый ответ", items: [
      { trackId: unrelated.id }, { trackId: candidates[0].id },
    ] };
  });
}

Deno.test("QUICK: «Ночная поездка» ranks editorial matches and works with untagged lo-fi tracks", async () => {
  const result = await preview("QUICK", { prompt: "Ночная поездка" }, [night.id, work.id]);
  assert.deepEqual(result.items, [{ trackId: night.id, position: 1, reason: null, availability: "EXTERNAL_ONLY" }]);
  await preview("QUICK", { prompt: "Ночная поездка" }, [work.id], [work, unrelated]);
});

Deno.test("ADVANCED: «Спокойный вечер» respects genre, mood and streaming filters", async () => {
  const inApp = { ...calm, track_sources: [{ ...verified, provider: "AUDIUS", can_stream: true }] };
  await preview("ADVANCED", {
    prompt: "Спокойный вечер", genreIds: [2], moodIds: [4], onlyInApp: true,
  }, [calm.id], [inApp, { ...inApp, id: paris.id, track_moods: [] }, { ...calm, id: work.id }, night]);
  await assert.rejects(preview("ADVANCED", {
    prompt: "Спокойный вечер", genreIds: [2], moodIds: [4], onlyInApp: true,
  }, []), (error: unknown) => error instanceof HttpError && error.code === "NO_CANDIDATES");
  await preview("QUICK", { prompt: "Спокойный вечер" }, [work.id], [work, unrelated]);
});

Deno.test("QUICK: «Музыка для работы» can use lo-fi title evidence in an untagged catalog", async () => {
  await preview("QUICK", { prompt: "Музыка для работы" }, [work.id], [work, unrelated]);
});

Deno.test("QUICK: a direct artist request matches the credited artist", async () => {
  const artist = {
    ...track(6, "Archangel"),
    track_artists: [{ credit_order: 1, artists: { name: "Burial" } }],
  };
  await preview("QUICK", { prompt: "Хочу песни Burial" }, [artist.id], [artist, unrelated]);
});

Deno.test("PLACE: uses the city editorial playlist with an optional prompt present", async () => {
  await preview("PLACE", { prompt: "Саундтрек к визиту", context: [{ title: "Париж" }] }, [paris.id]);
  await preview("PLACE", { context: [{ title: "Париж", description: "Настроение неспешной городской прогулки." }] },
    [work.id], [work, unrelated]);
});

Deno.test("JOURNEY: considers multiple stops and verified track links", async () => {
  await preview("JOURNEY", { context: [{ title: "Берлин" }, { title: "Париж" }] }, [night.id, paris.id]);
  await preview("JOURNEY", {
    context: [
      { title: "Берлин", description: "Клубная культура и контрастная архитектура." },
      { title: "Париж", description: "Неспешная городская прогулка." },
    ],
  }, [night.id, work.id], [{ ...night, title: "House Sunrise", playlist_tracks: [] }, work, unrelated]);
  await preview("JOURNEY", {
    context: [{ title: "Выезд" }, { title: "Остановка" }], pinnedTrackIds: [paris.id],
  }, [paris.id]);
});

Deno.test("No candidates: ignores unverified links, unsafe URLs, unrelated and private editorial tracks", async () => {
  const wrongHost = { ...verified, external_url: "https://music.apple.com.example.org/song/42" };
  const privateLink = editorial("Берлин: ночная поездка", "");
  privateLink.playlists.visibility = "PRIVATE";
  const unpublished = editorial("Берлин: ночная поездка", "");
  const draftLink = { ...unpublished, playlists: { ...unpublished.playlists, published_at: null } };
  const catalog = [
    unrelated,
    { ...night, track_sources: [{ ...verified, verified_at: null }] },
    { ...night, track_sources: [wrongHost] },
    { ...night, playlist_tracks: [privateLink] },
    { ...night, playlist_tracks: [draftLink] },
  ];
  await assert.rejects(preview("QUICK", { prompt: "Ночная поездка" }, [], catalog),
    (error: unknown) => error instanceof HttpError && error.status === 422 && error.code === "NO_CANDIDATES");
});

Deno.test("Unavailable network propagates and does not call the model", async () => {
  const failure = new HttpError(503, "DATABASE_UNAVAILABLE", "Database unavailable");
  await assert.rejects(generatePreview("QUICK", { ...request, prompt: "Ночная поездка" }, 1,
    undefined, (query, count) => findGenerationCandidates(query, count, undefined, () => Promise.reject(failure)),
    () => { throw new Error("Unexpected model call"); }), error => error === failure);
});

Deno.test("Generation prefers playable candidates and copies availability from the catalog", async () => {
  const inApp = { id: calm.id, title: calm.title, artist: "Artist", durationMs: 90_000,
    availability: "IN_APP" as const };
  const outside = { ...inApp, id: work.id, availability: "EXTERNAL_ONLY" as const };
  const preview = await generatePreview("QUICK", { ...request, prompt: "Ambient" }, 1, undefined,
    () => Promise.resolve([outside, inApp]), (_prompt, candidates) => {
      assert.deepEqual(candidates, [inApp]);
      return Promise.resolve({ title: "Ambient", description: "", items: [
        { trackId: outside.id }, { trackId: inApp.id, availability: "EXTERNAL_ONLY" },
      ] });
    });
  assert.deepEqual(preview.items, [{ trackId: inApp.id, position: 1, reason: null, availability: "IN_APP" }]);
});

Deno.test("Scans later catalog pages and uses editorial genres as hints only", async () => {
  const first = Array.from({ length: 100 }, (_, i) => track(i, "Unrelated"));
  const ambient = { ...track(100, "Test"), playlist_tracks: [
    editorial("Париж", "", 1, ["Downtempo"]),
  ] };
  let calls = 0;
  const read = async (_table: string, query: string) => {
    calls++;
    if (calls === 1) return first;
    assert.ok(query.endsWith(`&id=gt.${first[99].id}`));
    return [ambient];
  };
  assert.deepEqual((await findGenerationCandidates({ ...request, prompt: "Спокойный вечер" },
    2, undefined, read)).map(track => track.id), [ambient.id]);
  assert.equal(calls, 2);
});
