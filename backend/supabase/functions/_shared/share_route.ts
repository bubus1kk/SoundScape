import { HttpError, uuidRe } from './core.ts';

export const shareKinds = ['PLAYLIST', 'MUSIC_PIN', 'TRAVEL_EVENT', 'JOURNEY', 'PROFILE'];

export function sharePath(kind: string, id: string, token?: string): string {
  if (!shareKinds.includes(kind) || !uuidRe.test(id)) {
    throw new HttpError(400, 'VALIDATION_ERROR', 'Invalid share target');
  }
  return `/share/${kind}/${id.toLowerCase()}${token ? `#${encodeURIComponent(token)}` : ''}`;
}

export function parseSharePath(path: string): { objectType: string; objectId: string } {
  const result = /(?:^|\/)share\/([^/]+)\/([^/]+)\/?$/.exec(path);
  if (!result) throw new HttpError(404, 'NOT_FOUND', 'Share route not found');
  sharePath(result[1], result[2]);
  return { objectType: result[1], objectId: result[2].toLowerCase() };
}

function safeJson(value: unknown): string {
  return JSON.stringify(value).replaceAll('<', '\\u003c');
}

export function sharePage(path: string, apikey: string, nonce: string, installUrl?: string): Response {
  const target = parseSharePath(path);
  const html = `<!doctype html>
<html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SoundScape</title>
<main><h1 id="title">SoundScape</h1>
<p id="description" role="status">Загрузка…</p>
<noscript>Для просмотра ссылки требуется JavaScript.</noscript>
<p><a id="install" hidden>Установить SoundScape</a></p></main>
<script nonce="${nonce}">
const target = ${safeJson(target)};
const installUrl = ${safeJson(installUrl ?? '')};
const title = document.getElementById('title');
const description = document.getElementById('description');
if (installUrl) {
  const install = document.getElementById('install');
  install.href = installUrl;
  install.hidden = false;
}
(async () => {
  if (location.hash) target.token = decodeURIComponent(location.hash.slice(1));
  const result = await fetch(location.pathname, {
    method: 'POST',
    headers: {'content-type': 'application/json', apikey: ${safeJson(apikey)}},
    body: JSON.stringify(target)
  });
  if (!result.ok) {
    description.textContent = result.status === 404 ? 'Материал недоступен.' : 'Не удалось открыть материал.';
    return;
  }
  const value = await result.json();
  title.textContent = value.title;
  description.textContent = value.payload?.description ?? value.payload?.body ?? '';
})().catch(() => { description.textContent = 'Не удалось открыть материал.'; });
</script></html>`;
  return new Response(html, { headers: {
    'content-type': 'text/html; charset=utf-8',
    'cache-control': 'no-store',
    'referrer-policy': 'no-referrer',
    'x-content-type-options': 'nosniff',
    'content-security-policy': `default-src 'none'; script-src 'nonce-${nonce}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'`,
  } });
}
