import assert from 'node:assert/strict';
import search from '../../functions/v1-search/index.ts';
import searchTracks from '../../functions/v1-search-tracks/index.ts';
import resolveTrack from '../../functions/v1-resolve-track/index.ts';
import generatePlaylist from '../../functions/v1-generate-playlist/index.ts';
import openShare from '../../functions/v1-open-share/index.ts';
import manageShare from '../../functions/v1-manage-share/index.ts';
import media from '../../functions/v1-media/index.ts';
import { select } from '../../functions/_shared/core.ts';

const localOrigin = 'http://127.0.0.1:56321';
const providerUrl = 'https://creatornode.audius.co/stabilization-fixture';
const mediaHeaders = { 'content-type': 'audio/mpeg', 'content-range': 'bytes 0-11/9999' };

for (const keyKind of ['current', 'legacy'] as const) Deno.test(`catalog and Edge handlers with ${keyKind} credentials`, async () => {
  const anon = Deno.env.get('STAB_LOCAL_ANON_KEY');
  const service = Deno.env.get('STAB_LOCAL_SERVICE_KEY');
  const secret = Deno.env.get('STAB_LOCAL_SECRET_KEY');
  assert.ok(anon && service && secret, 'Use credentials from the disposable ss-stab-fresh project');
  Deno.env.set('SUPABASE_URL', localOrigin);
  Deno.env.set('SUPABASE_PUBLISHABLE_KEY', anon);
  Deno.env.set('SUPABASE_SECRET_KEY', keyKind === 'legacy' ? service : secret);
  Deno.env.set('GIGACHAT_AUTH_KEY', 'fixture-only');
  Deno.env.delete('AUDIUS_API_KEY');
  Deno.env.delete('AUDIUS_CONTENT_HOSTS');
  Deno.env.delete('SUPABASE_PUBLISHABLE_KEYS');
  Deno.env.delete('SUPABASE_SECRET_KEYS');

  const realFetch = globalThis.fetch;
  const prefix = crypto.randomUUID().replaceAll('-', '').slice(0, 10);
  const id = 'Stab' + prefix;
  const title = 'Stabilization ' + prefix;
  const tracks = ['Alpha', 'Beta', 'Restricted', 'Missing'].map((suffix, index) => ({
    id: id + index, title: title + ' ' + suffix, user: { name: 'Fixture artist' },
    duration: 130, permalink: '/stab/' + prefix + '-' + index, genre: 'Ambient',
    mood: 'Peaceful', is_streamable: index !== 2, access: { stream: index !== 2 },
  }));
  let loseNetwork = false;
  let malformedStream = false;
  let malformedMetadata = false;
  let activeProbes = 0;
  let peakProbes = 0;
  let generationIds: string[] = [];

  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.origin === localOrigin) return realFetch(input, init);
    if (url.hostname === 'api.audius.co') {
      if (loseNetwork) return Promise.reject(new TypeError('Fixture network unavailable'));
      if (url.pathname.endsWith('/search')) {
        const found = tracks.filter(track => track.title.includes(url.searchParams.get('query') ?? ''));
        return Promise.resolve(Response.json({ data: [...found, ...found.slice(0, 1)] }));
      }
      const track = tracks.find(track => url.pathname.startsWith('/v1/tracks/' + track.id));
      if (!track || track === tracks[3]) return Promise.resolve(new Response(null, { status: 404 }));
      if (url.pathname.endsWith('/stream')) {
        peakProbes = Math.max(peakProbes, ++activeProbes);
        try {
          await new Promise(resolve => setTimeout(resolve, 5));
          return new Response(null, { status: 302, headers: { location: providerUrl } });
        } finally {
          activeProbes--;
        }
      }
      return Response.json({ data: malformedMetadata ? null : track });
    }
    if (url.href === providerUrl) {
      const bytes = malformedStream ? 'invalid data' : 'ID3\0\0\0\0\0\0\0\0\0';
      return new Response(bytes, { status: 206, headers: mediaHeaders });
    }
    if (url.hostname === 'ngw.devices.sberbank.ru') {
      return Promise.resolve(Response.json({ access_token: 'fixture-oauth' }));
    }
    if (url.hostname === 'api.giga.chat') {
      const prompt = JSON.parse(String(init?.body)).messages[1].content as string;
      generationIds = [...prompt.matchAll(/"id":"([^"]+)"/g)].map(match => match[1]);
      assert.ok(generationIds.length);
      const content = JSON.stringify({
        title: 'Fixture result', description: 'Controlled model',
        items: [{ trackId: '00000000-0000-4000-8000-000000000000' }, { trackId: generationIds[0] }],
      });
      return Promise.resolve(Response.json({ choices: [{ message: { content } }] }));
    }
    return Promise.reject(new Error('Unexpected outbound origin in isolated integration test'));
  };

  async function json(path: string, payload: unknown, key: string, token?: string, expected = 200) {
    const response = await realFetch(localOrigin + path, {
      method: 'POST', body: JSON.stringify(payload),
      headers: { apikey: key, 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    });
    assert.equal(response.status, expected, path);
    return await response.json();
  }

  async function edgeRequest(handler: typeof search, payload: unknown, token?: string) {
    return await handler.fetch(new Request(localOrigin, {
      method: 'POST', body: JSON.stringify(payload),
      headers: { apikey: anon!, 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    }));
  }

  async function invoke(handler: typeof search, payload: unknown, expected = 200, token?: string) {
    const response = await edgeRequest(handler, payload, token);
    assert.equal(response.status, expected, JSON.stringify(payload));
    return await response.json();
  }

  async function account(suffix: string): Promise<{ id: string; token: string }> {
    const email = prefix + suffix + '@example.invalid';
    const password = crypto.randomUUID();
    const user = await json('/auth/v1/admin/users', { email, password, email_confirm: true }, service!, service!);
    const session = await json('/auth/v1/token?grant_type=password', { email, password }, anon!);
    return { id: user.id, token: session.access_token };
  }

  try {
    const first = await invoke(searchTracks, { query: title, limit: 1 });
    const second = await invoke(searchTracks, { query: title, limit: 1, offset: 1 });
    const third = await invoke(searchTracks, { query: title, limit: 1, offset: 2 });
    assert.equal(first.hasMore, true);
    assert.equal(second.hasMore, true);
    assert.equal(third.hasMore, false);
    assert.equal(new Set([first.items[0].id, second.items[0].id, third.items[0].id]).size, 3);
    assert.equal(third.items[0].availability, 'EXTERNAL_ONLY');
    const same = await invoke(search, { query: title, categories: ['TRACK'], limit: 1 });
    assert.equal(same.items[0].id, first.items[0].id);
    assert.equal(same.items[0].subtitle, 'Fixture artist');
    const all = await invoke(search, { query: title, limit: 50 });
    assert.deepEqual(new Set(all.items.filter((item: { category: string }) => item.category === 'TRACK')
      .map((item: { id: string }) => item.id)), new Set([first.items[0].id, second.items[0].id, third.items[0].id]));
    assert.equal((await invoke(searchTracks, { query: title, onlyInApp: true })).items.length, 2);
    const source = await invoke(resolveTrack, { trackId: first.items[0].id });
    assert.equal(source.streamUrl, providerUrl);
    assert.equal(source.availability, 'IN_APP');
    assert.equal(source.cacheAllowed, false);
    assert.equal(source.downloadAllowed, false);
    assert.equal((await invoke(resolveTrack, { trackId: third.items[0].id })).availability, 'EXTERNAL_ONLY');
    await invoke(resolveTrack, { trackId: crypto.randomUUID() }, 404);
    loseNetwork = true;
    await invoke(resolveTrack, { trackId: first.items[0].id }, 503);
    await invoke(searchTracks, { query: prefix + ' nonexistent' }, 503);
    loseNetwork = false;
    const readVerdict = async () => (await select(
      'track_sources', 'select=can_stream,verified_at&track_id=eq.' + first.items[0].id, null, true,
    ))[0];
    const confirmed = await readVerdict();
    assert.equal(confirmed.can_stream, true);
    malformedStream = true;
    const malformed = await invoke(resolveTrack, { trackId: first.items[0].id }, 502);
    assert.equal(malformed.error.code, 'PROVIDER_INVALID_RESPONSE');
    assert.deepEqual(await readVerdict(), confirmed, 'malformed audio never persists a permanent restriction');
    malformedStream = false;
    malformedMetadata = true;
    const emptyMetadata = await invoke(resolveTrack, { trackId: first.items[0].id }, 502);
    assert.equal(emptyMetadata.error.code, 'PROVIDER_INVALID_RESPONSE');
    assert.deepEqual(await readVerdict(), confirmed, 'HTTP 200 with empty metadata does not establish removal');
    malformedMetadata = false;

    const { id: ownerId, token: ownerToken } = await account('a');
    const { token: otherToken } = await account('b');
    const generated = await invoke(generatePlaylist, { mode: 'QUICK', prompt: title, count: 2, onlyInApp: true },
      200, ownerToken);
    assert.equal(generated.items.length, 1);
    assert.equal(generated.items[0].availability, 'IN_APP');
    assert.deepEqual(new Set(generationIds), new Set([first.items[0].id, second.items[0].id]));

    const playlistId = crypto.randomUUID();
    const coverPath = 'users/' + ownerId + '/cover.png';
    const cover = Uint8Array.from(atob(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ZQAAAAASUVORK5CYII=',
    ), character => character.charCodeAt(0));
    const upload = await realFetch(localOrigin + '/storage/v1/object/soundscape-media/' + coverPath, {
      method: 'POST', body: cover,
      headers: { apikey: anon, authorization: 'Bearer ' + ownerToken, 'content-type': 'image/png' },
    });
    assert.equal(upload.status, 200, 'owner can upload a test cover to private Storage');
    await upload.body?.cancel();
    const payload = { playlistId, title, coverPath, visibility: 'UNLISTED', trackIds: [first.items[0].id] };
    await json('/rest/v1/rpc/save_playlist', payload, anon, ownerToken);
    await json('/rest/v1/rpc/save_playlist', payload, anon, ownerToken, 409);
    await json('/rest/v1/rpc/save_playlist', { ...payload, expectedRevision: 99 }, anon, ownerToken, 409);
    await json('/rest/v1/rpc/get_playlist', { playlistId }, anon, otherToken, 404);
    await json('/rest/v1/rpc/get_playlist', { playlistId }, anon, undefined, 404);
    await json('/rest/v1/rpc/get_playlist', { playlistId }, anon, ownerToken);
    const mediaRequest = { objectType: 'PLAYLIST', objectId: playlistId };
    const ownedMedia = await edgeRequest(media, mediaRequest, ownerToken);
    assert.equal(ownedMedia.status, 200);
    assert.equal(ownedMedia.headers.get('content-type'), 'image/png');
    assert.deepEqual(new Uint8Array(await ownedMedia.arrayBuffer()), cover);
    await invoke(media, mediaRequest, 404, otherToken);
    await invoke(media, mediaRequest, 404);
    const share = await invoke(manageShare, { action: 'CREATE', objectType: 'PLAYLIST', objectId: playlistId },
      201, ownerToken);
    assert.equal((await invoke(openShare, { token: share.token })).id, playlistId);
    await invoke(manageShare, { action: 'REVOKE', shareId: share.shareId }, 404, otherToken);
    await invoke(manageShare, { action: 'REVOKE', shareId: share.shareId }, 200, ownerToken);
    await invoke(openShare, { token: share.token }, 404);

    const batchTitle = 'Capacity ' + prefix;
    tracks.push(...Array.from({ length: 5 }, (_, index) => ({
      ...tracks[0], id: id + 'batch' + index, title: batchTitle + ' ' + index, permalink: '/stab/batch-' + prefix + index,
    })));
    peakProbes = 0;
    const batch = await invoke(searchTracks, { query: batchTitle, onlyInApp: true });
    assert.equal(batch.items.length, 5);
    assert.equal(peakProbes, 3);
  } finally {
    globalThis.fetch = realFetch;
  }
});
