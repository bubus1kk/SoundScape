import assert from 'node:assert/strict';
import { ownedPaths } from '../../functions/_shared/media_cleanup.ts';
import { parseSharePath, sharePage, sharePath } from '../../functions/_shared/share_route.ts';
import { pinContext } from '../../functions/v1-generate-playlist/index.ts';

const userId = 'a74b6f47-07d1-4379-9417-d29b4ecc9c8f';
const otherId = 'd8c16943-ef78-4ac5-b83c-8e85024ff924';

Deno.test('Share fragment is excluded from the HTTP request path', () => {
  const url = new URL(sharePath('PLAYLIST', userId, 'secret'), 'https://example.invalid');
  if (url.hash !== '#secret' || url.pathname.includes('secret')) throw Error('Secret in request path');
  if (parseSharePath(url.pathname).objectId !== userId) throw Error('Unexpected target');
});

Deno.test('Fallback uses same-origin POST and isolates inline data', async () => {
  const response = sharePage(sharePath('PROFILE', userId), '</script><script>bad', 'test-nonce');
  const html = await response.text();
  if (html.includes('</script><script>bad') || !html.includes("method: 'POST'")) throw Error('Unsafe fallback');
  if (response.headers.get('referrer-policy') !== 'no-referrer' ||
      !response.headers.get('content-security-policy')?.includes("'nonce-test-nonce'")) {
    throw Error('Missing web response protection');
  }
});

Deno.test('Privileged Storage batch accepts only paths owned by caller', () => {
  const path = `users/${userId}/photo.png`;
  if (ownedPaths([path], userId, 1)[0] !== path) throw Error('Valid path rejected');
  for (const candidate of [[`users/${otherId}/photo.png`], ['editorial/photo.png'], [path, path]]) {
    let accepted = false;
    try {
      ownedPaths(candidate, userId, 1);
      accepted = true;
    } catch {
      // The helper must fail before any privileged request can be made.
    }
    if (accepted) throw Error('Invalid path accepted');
  }
});

Deno.test('PLACE generation reads public pin context with the user credential only', async () => {
  const deadline = AbortSignal.timeout(1000);
  const result = await pinContext(userId, 'fixture-jwt', undefined, deadline,
    async (table, query, token, privileged, _ctx, signal) => {
      assert.equal(table, 'music_pins');
      assert.equal(query, `select=title,description,pin_tracks(track_id)&id=eq.${userId}&visibility=eq.PUBLIC&limit=1`);
      assert.equal(token, 'fixture-jwt');
      assert.equal(privileged, false);
      assert.equal(signal, deadline);
      return [{ id: userId, title: 'Public place', description: 'Atmosphere', pin_tracks: [{ track_id: otherId }] }];
    });
  assert.deepEqual(result, {
    context: [{ title: 'Public place', description: 'Atmosphere' }], pinnedTrackIds: [otherId],
  });
  await assert.rejects(() => pinContext(userId, 'fixture-jwt', undefined, deadline, async () => []),
    { status: 422, code: 'INVALID_PLACE' });
});
