import assert from 'node:assert/strict';
import media from '../../functions/v1-media/index.ts';

function localEnv(variable: string) {
  return Deno.permissions.querySync({ name: 'env', variable }).state === 'granted'
    ? Deno.env.get(variable) : undefined;
}
const port = localEnv('BE02_LOCAL_PORT');
const mailPort = localEnv('BE02_LOCAL_MAIL_PORT');
const mailHost = localEnv('BE02_LOCAL_MAIL_HOST') ?? '127.0.0.1';
const apiKey = localEnv('BE02_LOCAL_ANON_KEY');
const serviceKey = localEnv('BE02_LOCAL_SERVICE_KEY');
const jwtSecret = localEnv('BE02_LOCAL_JWT_SECRET');
const base = `http://127.0.0.1:${port}`;
const enabled = /^\d{4,5}$/.test(port ?? '') && !!apiKey && !!serviceKey;
const png = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=='), c => c.charCodeAt(0));

function equal(actual: unknown, expected: unknown, label: string) {
  if (actual !== expected) throw new Error(`${label}: expected ${expected}, got ${actual}`);
}

async function request(path: string, status: number | number[], options: {
  method?: string; token?: string; admin?: boolean; data?: unknown; raw?: Uint8Array<ArrayBuffer>;
  headers?: Record<string, string>;
} = {}) {
  const { method = 'POST', token, admin, data, raw, headers } = options;
  const label = `${method} ${path.split('?')[0]}`.replace(/users\/[0-9a-f-]+.*$/i, 'users/<fixture>');
  const result = await fetch(base + path, {
    method,
    headers: {
      apikey: (admin ? serviceKey : apiKey)!,
      'content-type': raw ? 'image/png' : 'application/json',
      ...(admin || token ? { authorization: `Bearer ${admin ? serviceKey : token}` } : {}),
      ...headers,
    },
    body: raw ?? (data !== undefined ? JSON.stringify(data) : undefined),
    signal: AbortSignal.timeout(20_000),
    redirect: 'manual',
  }).catch((cause) => { throw Error(`${label}: transport failure`, { cause }); });
  const type = result.headers.get('content-type') ?? '';
  const content = await result.text();
  const accepted = Array.isArray(status) ? status : [status];
  if (!accepted.includes(result.status)) {
    throw Error(`${label}: expected HTTP ${accepted.join('/')}, got ${result.status}`);
  }
  return type.includes('json') && content ? JSON.parse(content) : content;
}

const rpc = (name: string, data: unknown, status: number | number[], token?: string) =>
  request('/rest/v1/rpc/' + name, status, { data, token });
const edge = (name: string, data: unknown, status: number, token?: string, headers?: Record<string, string>) =>
  request('/functions/v1/v1-' + name, status, { data, token, headers });
const signIn = (email: string, password: string, status = 200) =>
  request('/auth/v1/token?grant_type=password', status, { data: { email, password } });

async function signLocalAccessToken(current: string, secret: string, expiresIn: number) {
  const encode = (raw: string) => btoa(raw).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  const payload = atob(current.split('.')[1].replaceAll('-', '+').replaceAll('_', '/'));
  const claims = JSON.parse(payload);
  const now = Math.floor(Date.now() / 1000);
  claims.exp = now + expiresIn;
  claims.iat = now - 3600;
  const signingInput = encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })) + '.' + encode(JSON.stringify(claims));
  const encoder = new TextEncoder();
  const signingKey = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' },
    false, ['sign']);
  const signature = new Uint8Array(await crypto.subtle.sign('HMAC', signingKey, encoder.encode(signingInput)));
  return signingInput + '.' + encode(String.fromCharCode(...signature));
}

Deno.test({ name: 'BE-02 HTTP identity, replay, links, media and account deletion', ignore: !enabled, async fn() {
  const password = crypto.randomUUID();
  const track = crypto.randomUUID();
  const users = [];
  for (const suffix of ['a', 'b']) {
    const email = `be02-${crypto.randomUUID()}-${suffix}@example.invalid`;
    const account = await request('/auth/v1/admin/users', 200, {
      admin: true, data: { email, password, email_confirm: true, user_metadata: { role: 'ADMIN' } },
    });
    const session = await signIn(email, password);
    users.push({ id: account.id, token: session.access_token });
  }
  const [owner, reader] = users;
  const id = crypto.randomUUID();
  const replayKey = crypto.randomUUID();
  try {
    await request('/rest/v1/tracks', 201, { admin: true, data: { id: track, title: 'Local QA', duration_ms: 3500 } });
    equal((await rpc('get_my_profile', {}, 200, owner.token)).role, 'USER', 'Server-owned role');
    await rpc('update_my_profile', { role: 'ADMIN' }, 422, owner.token);
    await rpc('get_my_profile', {}, 401, 'broken.jwt.token');
    if (jwtSecret) {
      const valid = await signLocalAccessToken(owner.token, jwtSecret, 120);
      await rpc('get_my_profile', {}, 200, valid);
      const expired = await signLocalAccessToken(owner.token, jwtSecret, -120);
      await rpc('get_my_profile', {}, 401, expired);
      await edge('delete-account', { confirm: true }, 401, expired);
    }
    const publicProfile = await rpc('get_public_profile', { profileId: owner.id }, 200);
    equal('email' in publicProfile || 'role' in publicProfile, false, 'Public fields');

    const replay = { playlistId: id, title: 'Unlisted playlist', visibility: 'UNLISTED',
      trackIds: [track], idempotencyKey: replayKey };
    const first = await Promise.all([rpc('save_playlist', replay, 200, owner.token), rpc('save_playlist', replay, 200, owner.token)]);
    equal(JSON.stringify(first[0]), JSON.stringify(first[1]), 'Concurrent replay result');
    equal(first[0].revision, 1, 'One persisted revision');
    await rpc('save_playlist', { ...replay, title: 'Different' }, 409, owner.token);
    await rpc('get_playlist', { playlistId: id }, 404);
    await rpc('get_playlist', { playlistId: id }, 404, reader.token);
    await rpc('delete_playlist', { id, expectedRevision: 1 }, 404, reader.token);

    const path = `users/${owner.id}/${crypto.randomUUID()}.png`;
    const storage = '/storage/v1/object/soundscape-media/' + path;
    await request(storage + '.mp3', [400, 415], { token: owner.token, raw: png, headers: { 'content-type': 'audio/mpeg' } });
    await request(storage, [400, 403, 404], { token: reader.token, raw: png });
    await request(storage, 200, { token: owner.token, raw: png });
    await request('/storage/v1/object/sign/soundscape-media/' + path, [400, 403, 404],
      { token: owner.token, data: { expiresIn: 60 } });
    const update = { ...replay, expectedRevision: 1, coverPath: path };
    const attempts = await Promise.all([
      rpc('save_playlist', { ...update, idempotencyKey: crypto.randomUUID() }, [200, 409], owner.token),
      rpc('save_playlist', { ...update, idempotencyKey: crypto.randomUUID() }, [200, 409], owner.token),
    ]);
    equal(attempts.filter(result => result.revision === 2).length, 1, 'Only one concurrent revision update wins');
    equal(attempts.filter(result => result.message === 'VERSION_CONFLICT').length, 1, 'Explicit concurrent conflict');
    await edge('media', { objectType: 'PLAYLIST', objectId: id }, 404, reader.token);

    const link = await edge('manage-share', { action: 'CREATE', objectType: 'PLAYLIST', objectId: id }, 201, owner.token);
    equal(link.routePath.split('#')[0], `/share/PLAYLIST/${id}`, 'Domain-independent URL');
    const opened = await edge('open-share', { token: link.token, objectType: 'PLAYLIST', objectId: id }, 200);
    equal(opened.id, id, 'Token opens target');
    await edge('open-share', { token: link.token, objectType: 'PLAYLIST', objectId: crypto.randomUUID() }, 404);
    await edge('media', { objectType: 'PLAYLIST', objectId: id }, 200, undefined, { 'x-soundscape-share-token': link.token });
    await edge('manage-share', { action: 'REVOKE', shareId: link.shareId }, 200, owner.token);
    await edge('open-share', { token: link.token }, 404);

    const old = await edge('manage-share', { action: 'CREATE', objectType: 'PLAYLIST', objectId: id }, 201, owner.token);
    await rpc('save_playlist', { ...update, visibility: 'PUBLIC', expectedRevision: 2, idempotencyKey: crypto.randomUUID() }, 200, owner.token);
    const like = { kind: 'PLAYLIST_LIKE', targetId: id, active: true };
    equal((await rpc('social_state', like, 200, reader.token)).count, 1, 'Reader likes public playlist');
    equal((await rpc('social_state', like, 200, reader.token)).count, 1, 'Retry does not duplicate likes');
    equal((await rpc('social_state', { kind: 'PLAYLIST_LIKE', targetId: id }, 200)).count, 1, 'Guest sees public count');
    await rpc('social_state', { kind: 'SAVED_PLAYLIST', targetId: id, active: true }, 200, reader.token);
    await rpc('social_state', { kind: 'FAVORITE', targetId: track, active: true }, 200, reader.token);
    equal((await rpc('list_my_collection', { kind: 'FAVORITE' }, 200, reader.token)).items.length, 1, 'Reader sees own favorite');
    await edge('open-share', { objectType: 'PLAYLIST', objectId: id }, 200);
    const webRoute = `/functions/v1/v1-open-share/share/PLAYLIST/${id}`;
    const page = await request(webRoute, 200, { method: 'GET' });
    equal(page.includes('Установить SoundScape'), true, 'Web fallback HTML');
    await request(webRoute, 200, { data: { objectType: 'PLAYLIST', objectId: id } });
    await request(webRoute, 404, { data: { objectType: 'PROFILE', objectId: owner.id } });
    await rpc('save_playlist', { ...update, expectedRevision: 3, idempotencyKey: crypto.randomUUID() }, 200, owner.token);
    await edge('open-share', { token: old.token }, 404);
    equal((await rpc('list_my_collection', { kind: 'SAVED_PLAYLIST' }, 200, reader.token)).items.length, 0, 'Closed playlist disappears from saved items');

    const command = { id, expectedRevision: 4, idempotencyKey: crypto.randomUUID() };
    await rpc('delete_playlist', { ...command, expectedRevision: 3 }, 409, owner.token);
    const lostResponse = fetch(base + '/rest/v1/rpc/delete_playlist', {
      method: 'POST', body: JSON.stringify(command),
      headers: { apikey: apiKey!, authorization: 'Bearer ' + owner.token, 'content-type': 'application/json' },
      signal: AbortSignal.timeout(20_000),
    }).then(async first => {
      equal(first.status, 200, 'Delete retries successfully after a stale revision');
      await first.body?.cancel();
    });
    const [, concurrentReceipt] = await Promise.all([lostResponse, rpc('delete_playlist', command, 200, owner.token)]);
    equal(concurrentReceipt.success, true, 'Concurrent delete acknowledged');
    equal(JSON.stringify(await rpc('delete_playlist', command, 200, owner.token)), JSON.stringify(concurrentReceipt),
      'Lost response replay returns the same receipt');
    await rpc('delete_playlist', { ...command, expectedRevision: 5 }, 409, owner.token);
    await rpc('get_playlist', { playlistId: id }, 404, owner.token);
    equal((await request('/rest/v1/playlist_tracks?playlist_id=eq.' + id, 200,
      { method: 'GET', admin: true })).length, 0, 'Delete cascades to tracks in the same transaction');
    equal((await edge('media', { action: 'CLEANUP' }, 200, owner.token)).processed, 1, 'Detached physical media cleanup');
    equal((await edge('media', { action: 'CLEANUP' }, 200, owner.token)).processed, 0, 'Repeat cleanup');

    const avatar = `users/${owner.id}/${crypto.randomUUID()}.png`;
    await request('/storage/v1/object/soundscape-media/' + avatar, 200, { token: owner.token, raw: png });
    await rpc('update_my_profile', { avatarPath: avatar }, 200, owner.token);
    equal((await rpc('get_public_profile', { profileId: owner.id }, 200)).hasAvatar, true, 'Public avatar flag');
    await edge('media', { objectType: 'PROFILE', objectId: owner.id }, 200);
    const sharedId = crypto.randomUUID();
    await rpc('save_playlist', { ...replay, playlistId: sharedId, idempotencyKey: crypto.randomUUID(),
      coverPath: avatar }, 200, owner.token);
    await rpc('update_my_profile', { avatarPath: null }, 200, owner.token);
    await edge('media', { objectType: 'PROFILE', objectId: owner.id }, 404);
    equal((await edge('media', { action: 'CLEANUP' }, 200, owner.token)).processed, 0, 'Shared image is retained');
    await edge('media', { objectType: 'PLAYLIST', objectId: sharedId }, 200, owner.token);
    await request('/storage/v1/object/soundscape-media/' + avatar, [200, 400, 401, 403, 404],
      { method: 'DELETE', token: reader.token });
    await edge('media', { objectType: 'PLAYLIST', objectId: sharedId }, 200, owner.token);

    const edit = { ...replay, playlistId: sharedId, expectedRevision: 1, idempotencyKey: crypto.randomUUID() };
    const remove = { id: sharedId, expectedRevision: 1, idempotencyKey: crypto.randomUUID() };
    const [updated, deleted] = await Promise.all([
      rpc('save_playlist', edit, [200, 404], owner.token),
      rpc('delete_playlist', remove, [200, 409], owner.token),
    ]);
    equal(updated.revision === 2, deleted.message === 'VERSION_CONFLICT', 'Edit and delete serialize');
    if (updated.revision === 2) {
      equal((await request('/rest/v1/playlist_tracks?playlist_id=eq.' + sharedId, 200,
        { method: 'GET', admin: true })).length, 1, 'Version conflict preserves child records');
      await rpc('delete_playlist', { ...remove, expectedRevision: 2 }, 200, owner.token);
    } else {
      equal(updated.message, 'PLAYLIST_NOT_FOUND', 'Edit cannot recreate a deleted playlist');
    }
    // Leave the queued row in the database and partially process it with the real Storage API.
    const claimed = await request('/rest/v1/rpc/edge_claim_media_cleanup', 200,
      { admin: true, data: { p_user: owner.id } });
    equal(claimed.includes(avatar), true, 'Detached file persisted across client disconnect');
    await request('/storage/v1/object/soundscape-media', 200,
      { method: 'DELETE', admin: true, data: { prefixes: [avatar] } });
    equal((await edge('media', { action: 'CLEANUP' }, 200, owner.token)).processed, 1, 'Interrupted cleanup resumes');
    equal((await edge('media', { action: 'CLEANUP' }, 200, owner.token)).processed, 0, 'Interrupted cleanup is complete');

    const remaining = `users/${owner.id}/${crypto.randomUUID()}.png`;
    await request('/storage/v1/object/soundscape-media/' + remaining, 200, { token: owner.token, raw: png });
    await edge('delete-account', { confirm: true, userId: reader.id }, 422, owner.token);
    await edge('delete-account', { confirm: true }, 401);
    await request('/rest/v1/rpc/edge_begin_account_deletion', 200, { admin: true, data: { p_user: owner.id } });
    await rpc('get_my_profile', {}, 401, owner.token);
    await request('/storage/v1/object/soundscape-media/' + remaining,
      [400, 401, 403, 404], { method: 'GET', token: owner.token });
    equal((await edge('delete-account', { confirm: true }, 200, owner.token)).status, 'DELETED', 'Auth account removed');
    await rpc('get_my_profile', {}, 401, owner.token);
    await rpc('get_public_profile', { profileId: owner.id }, 404);
    equal((await rpc('get_my_profile', {}, 200, reader.token)).id, reader.id, 'Reader remains active');
    const catalog = await request('/rest/v1/tracks?id=eq.' + track, 200, { method: 'GET' });
    equal(catalog.length, 1, 'Shared track remains present');
  } finally {
    for (const user of users) {
      const result = await fetch(base + '/auth/v1/admin/users/' + user.id, {
        method: 'DELETE', headers: { apikey: serviceKey!, authorization: `Bearer ${serviceKey}` },
      });
      await result.body?.cancel();
    }
    await request('/rest/v1/tracks?id=eq.' + track, 204, { method: 'DELETE', admin: true });
  }
}});

Deno.test({ name: 'BE-02 service completes a pending account deletion after logout', ignore: !enabled, async fn() {
  const email = `be02-pending-${crypto.randomUUID()}@example.invalid`;
  const password = crypto.randomUUID();
  const account = await request('/auth/v1/admin/users', 200, {
    admin: true, data: { email, password, email_confirm: true },
  });
  const { access_token: token } = await signIn(email, password);
  const prefix = `users/${account.id}/pending-`;
  const paths = Array.from({ length: 101 }, (_unused, i) => prefix + String(i).padStart(3, '0') + '.png');
  const endpoint = '/functions/v1/v1-delete-account';
  try {
    for (let i = 0; i < paths.length; i += 10) {
      await Promise.all(paths.slice(i, i + 10).map(path =>
        request('/storage/v1/object/soundscape-media/' + path, 200, { token, raw: png })));
    }
    await rpc('get_my_profile', {}, 200, token);
    equal((await edge('delete-account', { confirm: true }, 202, token)).status, 'PENDING', 'First batch leaves durable work');
    await request('/storage/v1/object/authenticated/soundscape-media/' + paths[100], 200,
      { method: 'GET', admin: true });
    await request('/auth/v1/logout?scope=local', 204, { token });
    await edge('delete-account', { confirm: true }, 401, token);
    await edge('delete-account', { action: 'PROCESS_PENDING' }, 401, token);
    const job = await request(endpoint, 200, { admin: true, data: { action: 'PROCESS_PENDING' } });
    equal(job.status, 'DELETED', 'Service resumes pending deletion after session revocation');
    await rpc('get_public_profile', { profileId: account.id }, 404);
    await request('/storage/v1/object/authenticated/soundscape-media/' + paths[100],
      [400, 404], { method: 'GET', admin: true });
    equal((await request(endpoint, 200, { admin: true, data: { action: 'PROCESS_PENDING' } })).status,
      'IDLE', 'Completed job does not run again');
  } finally {
    await request('/storage/v1/object/soundscape-media', 200,
      { method: 'DELETE', admin: true, data: { prefixes: paths } });
    await request('/auth/v1/admin/users/' + account.id, [200, 404], { method: 'DELETE', admin: true });
  }
}});

Deno.test({ name: 'BE-02 local email confirmation, recovery, refresh and logout',
  ignore: !enabled || !/^\d{4,5}$/.test(mailPort ?? '') || !['127.0.0.1', 'inbucket'].includes(mailHost), async fn() {
    const mailbox = 'be02-' + crypto.randomUUID();
    const email = mailbox + '@example.invalid';
    const password = crypto.randomUUID();
    const signup = await request('/auth/v1/signup', 200, { data: { email, password } });
    const id = signup.user?.id ?? signup.id;
    const mail = `http://${mailHost}:${mailPort}/api/v1`;
    const seen = new Set<string>();
    async function verifyMail(type: 'signup' | 'recovery') {
      for (let attempt = 0; attempt < 20; attempt++) {
        const list = await fetch(mail + '/messages', { signal: AbortSignal.timeout(2500) })
          .then(result => result.ok ? result.json() : null).catch(() => null);
        const delivery = list?.messages?.find((item: { ID: string; To: { Address: string }[] }) =>
          !seen.has(item.ID) && item.To.some(recipient => recipient.Address === email));
        if (delivery) {
          seen.add(delivery.ID);
          const message = await fetch(mail + '/message/' + encodeURIComponent(delivery.ID),
            { signal: AbortSignal.timeout(4000) }).then(result => result.json());
          const token = /token=([^&\s"<>]+)/.exec(message.HTML ?? message.Text ?? '')?.[1];
          if (!token) throw Error('Test email did not contain verification token');
          return request('/auth/v1/verify', 200, { data: { type, token_hash: decodeURIComponent(token) } });
        }
        await new Promise(resolve => setTimeout(resolve, 300));
      }
      throw Error('Local test email did not arrive');
    }
    try {
      await signIn(email, password, 400);
      await verifyMail('signup');
      const session = await signIn(email, password);
      const renewed = await request('/auth/v1/token?grant_type=refresh_token', 200,
        { data: { refresh_token: session.refresh_token } });
      equal((await rpc('get_my_profile', {}, 200, renewed.access_token)).id, id, 'Refreshed session owns profile');
      await request('/auth/v1/logout?scope=local', 204, { token: renewed.access_token });
      await rpc('get_my_profile', {}, 401, renewed.access_token);
      await edge('delete-account', { confirm: true }, 401, renewed.access_token);
      await request('/auth/v1/recover', 200, { data: { email } });
      const recovery = await verifyMail('recovery');
      const newPassword = crypto.randomUUID();
      await request('/auth/v1/user', 200, { method: 'PUT', token: recovery.access_token, data: { password: newPassword } });
      const signedIn = await signIn(email, newPassword);
      equal(signedIn.user.id, id, 'Recovered account keeps the same profile');
    } finally {
      await request('/auth/v1/admin/users/' + id, 200, { method: 'DELETE', admin: true });
    }
  }});

const fixtureOwner = 'a74b6f47-07d1-4379-9417-d29b4ecc9c8f';
Deno.test('Scheduled media cleanup requires a service key and retries without premature completion', async () => {
  const env = {
    SUPABASE_URL: 'https://fixture.invalid',
    SUPABASE_SECRET_KEYS: '{"default":"sb_secret_fixture"}',
    SUPABASE_SERVICE_ROLE_KEY: 'legacy.service.fixture',
  };
  const saved = Object.keys(env).map(name => [name, Deno.env.get(name)] as const);
  const originalFetch = globalThis.fetch;
  const path = `users/${fixtureOwner}/detached.png`;
  const calls: string[] = [];
  let storageAvailable = false;
  let pending = true;
  const invoke = (key: string) => media.fetch(new Request('https://fixture.invalid/v1-media', {
    method: 'POST',
    headers: { 'content-type': 'application/json', apikey: key, authorization: 'Bearer forwarded' },
    body: JSON.stringify({ action: 'PROCESS_PENDING' }),
  }));

  try {
    for (const [name, value] of Object.entries(env)) Deno.env.set(name, value);
    globalThis.fetch = (input, init) => {
      const route = new URL(String(input)).pathname.split('/').at(-1)!;
      calls.push(route);
      if (route === 'edge_claim_pending_media_cleanup') {
        return Promise.resolve(Response.json(pending ? { userId: fixtureOwner, paths: [path] } : null));
      }
      if (route === 'soundscape-media') {
        assert.equal(init?.method, 'DELETE');
        assert.deepEqual(JSON.parse(String(init?.body)), { prefixes: [path] });
        return Promise.resolve(new Response(null, { status: storageAvailable ? 204 : 503 }));
      }
      assert.equal(route, 'edge_complete_media_cleanup');
      assert.deepEqual(JSON.parse(String(init?.body)), { p_user: fixtureOwner, p_paths: [path] });
      pending = false;
      return Promise.resolve(new Response(null, { status: 204 }));
    };

    assert.equal((await invoke('sb_publishable_fixture')).status, 401);
    assert.deepEqual(calls, []);
    assert.equal((await invoke('sb_secret_fixture')).status, 503);
    assert.deepEqual(calls.splice(0), ['edge_claim_pending_media_cleanup', 'soundscape-media']);
    storageAvailable = true;
    assert.deepEqual(await (await invoke('legacy.service.fixture')).json(), { status: 'PROCESSED', processed: 1 });
    assert.deepEqual(calls.splice(0), ['edge_claim_pending_media_cleanup', 'soundscape-media', 'edge_complete_media_cleanup']);
    assert.deepEqual(await (await invoke('sb_secret_fixture')).json(), { status: 'IDLE' });
    assert.deepEqual(calls, ['edge_claim_pending_media_cleanup']);
  } finally {
    globalThis.fetch = originalFetch;
    for (const [name, value] of saved) {
      if (value === undefined) Deno.env.delete(name);
      else Deno.env.set(name, value);
    }
  }
});
