import assert from 'node:assert/strict';
import { audiusMetadata, probeAudiusStream } from '../../functions/_shared/audius.ts';
import { HttpError } from '../../functions/_shared/core.ts';

const sample = {
  id: 'V66vyPP', title: 'Track title', user: { name: 'Artist name' },
  duration: 150, permalink: '/artist/track', genre: 'Ambient', mood: 'Peaceful',
  is_streamable: true, access: { stream: true },
};
const metadata = audiusMetadata(sample);
assert.ok('track' in metadata);
const { track } = metadata;

Deno.test('Audius metadata never treats a preview or app allowlist as public full-track access', () => {
  for (const extra of [
    { is_stream_gated: true }, { access: { stream: false } },
    { allowed_api_keys: ['app-only'] }, { stream_conditions: { purchase: true } },
    { is_streamable: false },
  ]) {
    const result = audiusMetadata({ ...sample, ...extra });
    assert.ok('track' in result && result.restricted);
  }
  assert.deepEqual(audiusMetadata({ ...sample, is_available: false }), { reason: 'unavailable' });
  assert.deepEqual(audiusMetadata({ ...sample, is_unlisted: true }), { reason: 'non_public' });
  assert.ok(!('mood' in (audiusMetadata({ ...sample, field_visibility: { mood: false } }) as typeof metadata).track));
});

Deno.test('Audio probe checks ranged bytes, handles a signed redirect and keeps the Audius key server-side', async () => {
  const key = Deno.env.get('AUDIUS_API_KEY');
  const original = globalThis.fetch;
  const signedUrl = 'https://creatornode.audius.co/tracks/cidstream/a?X-Amz-Date=20261124T080000Z&X-Amz-Expires=3600';
  let hits = 0;
  try {
    Deno.env.set('AUDIUS_API_KEY', 'test-private-audius-auth');
    globalThis.fetch = (input, init) => {
      hits++;
      const url = new URL(String(input));
      const headers = new Headers(init?.headers);
      assert.equal(init?.redirect, 'manual');
      assert.equal(headers.get('range'), 'bytes=0-1023');
      if (url.hostname === 'api.audius.co') {
        assert.equal(headers.get('x-api-key'), 'test-private-audius-auth');
        return Promise.resolve(new Response(null, { status: 302, headers: { location: signedUrl } }));
      }
      assert.equal(headers.get('x-api-key'), null);
      return Promise.resolve(new Response('ID3\0\0\0\0\0\0\0\0\0', { status: 206,
        headers: { 'content-type': 'audio/mpeg', 'content-range': 'bytes 0-11/9999' } }));
    };
    assert.deepEqual(await probeAudiusStream(track), { url: signedUrl, expiresAt: '2026-11-24T09:00:00.000Z' });
    assert.equal(hits, 2);
  } finally {
    globalThis.fetch = original;
    if (key === undefined) Deno.env.delete('AUDIUS_API_KEY');
    else Deno.env.set('AUDIUS_API_KEY', key);
  }
});

Deno.test('HTTP success with a non-audio body, gated and missing sources cannot become IN_APP', async () => {
  const contentHosts = Deno.env.get('AUDIUS_CONTENT_HOSTS');
  const original = globalThis.fetch;
  try {
    Deno.env.delete('AUDIUS_CONTENT_HOSTS');
    for (const [status, expected] of [[403, 'restricted'], [404, 'missing_stream'], [206, 'invalid_audio']] as const) {
      globalThis.fetch = (input) => Promise.resolve(String(input).includes('api.audius.co')
        ? new Response(null, { status: 302, headers: { location: 'https://creatornode.audius.co/track' } })
        : new Response('not really a song', { status,
          headers: { 'content-type': 'audio/mpeg', 'content-range': 'bytes 0-16/9999' } }));
      assert.deepEqual(await probeAudiusStream(track), { reason: expected });
    }
    globalThis.fetch = () => Promise.resolve(new Response(null, {
      status: 302, headers: { location: 'https://127.0.0.1/secret' },
    }));
    await assert.rejects(probeAudiusStream(track), (e: unknown) =>
      e instanceof HttpError && e.code === 'PROVIDER_INVALID_RESPONSE');
  } finally {
    globalThis.fetch = original;
    if (contentHosts !== undefined) Deno.env.set('AUDIUS_CONTENT_HOSTS', contentHosts);
  }
});
