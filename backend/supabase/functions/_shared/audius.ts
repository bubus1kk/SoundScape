import { HttpError, isSafeExternalUrl, rpc } from './core.ts';
import { logInternalFailure } from './observability.ts';
import { invalidProviderResponse, networkFailure, providerHttpFailure } from './provider_errors.ts';
import type { ProviderStage } from './provider_errors.ts';
import type { RequestContext } from './observability.ts';

const apiOrigin = 'https://api.audius.co';
const redirects = [301, 302, 303, 307, 308];
const trackId = /^[A-Za-z0-9-]{1,120}$/;
const missingTrack = Symbol('Audius track missing');

export type AudiusTrack = {
  id: string; title: string; artist: string; durationMs: number; permalink: string;
  genre?: string; mood?: string; artistId?: string; isrc?: string; recordingMbid?: string;
};

type Metadata = { track: AudiusTrack; restricted: boolean } | { reason: string };
type Stream = { url: string; expiresAt: string | null } | { reason: string };

export function audiusMetadata(x: any): Metadata {
  if (x?.is_delete) return { reason: 'deleted' };
  if (x?.is_unlisted || x?.is_private || x?.is_scheduled_release) return { reason: 'non_public' };
  if (x?.is_available === false || x?.user?.is_deactivated) return { reason: 'unavailable' };
  const title = typeof x?.title === 'string' ? x.title.trim() : '';
  const artist = typeof x?.user?.name === 'string' ? x.user.name.trim() : '';
  const durationMs = typeof x?.duration === 'number' ? Math.round(x.duration * 1000) : 0;
  const permalink = typeof x?.permalink === 'string' && x.permalink.startsWith('/')
    ? 'https://audius.co' + x.permalink : x?.permalink;
  if (typeof x?.id !== 'string' || !trackId.test(x.id) ||
      typeof title !== 'string' || !title || title.length > 300 ||
      typeof artist !== 'string' || !artist || artist.length > 200 ||
      !Number.isInteger(durationMs) || durationMs < 1 || durationMs > 7200000 ||
      !isSafeExternalUrl(permalink)) return { reason: 'invalid_metadata' };
  const track: AudiusTrack = { id: x.id, title, artist, durationMs, permalink };
  if (typeof x.user?.id === 'string' && trackId.test(x.user.id)) track.artistId = x.user.id;
  if (typeof x.isrc === 'string' && /^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$/i.test(x.isrc)) track.isrc=x.isrc.toUpperCase();
  if (typeof x.musicbrainz_recording_id === 'string' &&
      /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(x.musicbrainz_recording_id))
    track.recordingMbid=x.musicbrainz_recording_id.toLowerCase();
  if (x.field_visibility?.genre !== false && typeof x.genre === 'string' && x.genre.trim())
    track.genre = x.genre.trim().slice(0, 80);
  if (x.field_visibility?.mood !== false && typeof x.mood === 'string' && x.mood.trim())
    track.mood = x.mood.trim().slice(0, 80);
  const restricted = x.is_streamable === false || x.is_stream_gated === true ||
    x.access?.stream === false || Object.keys(x.stream_conditions ?? {}).length > 0 ||
    (Array.isArray(x.allowed_api_keys) && x.allowed_api_keys.length > 0);
  return { track, restricted };
}

function apiKey() { return Deno.env.get('AUDIUS_API_KEY'); }

export function audiusOperationLimits(ctx?: RequestContext) {
  const read = (name: string, fallback: number) => {
    const value = ctx ? Deno.env.get(name) : undefined;
    if (value === undefined) return fallback;
    const limit = Number(value);
    if (!Number.isInteger(limit) || limit < 1 || limit > fallback)
      throw new HttpError(503,'PROVIDER_UNAVAILABLE','Invalid Audius operation limits');
    return limit;
  };
  return {
    parallel:read('AUDIUS_OPERATION_PARALLEL',3),
    updates:read('AUDIUS_OPERATION_UPDATES',6),
  };
}

export async function withAudiusProbe<T>(providerId: string, action: () => Promise<T>,
  ctx?: RequestContext, deadline?: AbortSignal): Promise<T> {
  if (!ctx) return await action();
  const token = crypto.randomUUID();
  const args = {p_provider_id:providerId,p_token:token};
  const acquired = await rpc('edge_audius_lease',args,ctx,deadline);
  if (acquired === false) throw new HttpError(429,'RATE_LIMITED','Audius verification is already in progress',1);
  if (acquired !== true) throw new HttpError(502,'DATABASE_RESPONSE_INVALID','Invalid Audius lease response');
  try { return await action(); }
  finally {
    try { await rpc('edge_audius_lease',{...args,p_release:true},ctx,AbortSignal.timeout(1500)); }
    catch { logInternalFailure(ctx,'LEASE_RELEASE_ERROR','edge_audius_lease'); }
  }
}

async function audiusFetch(
  url: URL, operation: Extract<ProviderStage, 'search' | 'metadata' | 'stream'>, headers: HeadersInit, ctx?: RequestContext, deadline?: AbortSignal,
  authenticated = true,
) {
  const requestHeaders = new Headers(headers);
  if (authenticated) {
    const key = apiKey();
    if (key) requestHeaders.set('x-api-key', key);
    url.searchParams.set('app_name', 'SoundScape');
  }
  if (ctx) {
    await ctx.paceAudiusRequest?.();
    const budget = await rpc('edge_audius_reserve',{},ctx,deadline);
    if (budget?.configured !== true)
      throw new HttpError(503,'PROVIDER_UNAVAILABLE','Audius request budget is unavailable');
    if (budget.allowed !== true)
      throw new HttpError(429,'RATE_LIMITED','Audius request budget exhausted',budget.retryAfter);
  }
  await ctx?.beforeAudiusRequest?.(operation);
  const timeout = AbortSignal.timeout(8000);
  const response = await fetch(url, {
    headers: requestHeaders, redirect: 'manual', signal: deadline ? AbortSignal.any([timeout, deadline]) : timeout,
  }).catch(e => { throw networkFailure('AUDIUS', operation, e, ctx); });
  if (ctx && response.status === 429) {
    const limited = providerHttpFailure('AUDIUS',operation,429,response.headers.get('retry-after'),ctx);
    await response.body?.cancel();
    await rpc('edge_audius_reserve',{p_cooldown_seconds:limited.retryAfter ?? 1},ctx,AbortSignal.timeout(2000));
    throw limited;
  }
  return response;
}

async function audiusJson(
  path: string, params: Record<string, string | string[]>, ctx?: RequestContext, deadline?: AbortSignal,
): Promise<unknown> {
  const url = new URL(apiOrigin + '/v1/tracks' + path);
  for (const [key, values] of Object.entries(params))
    for (const value of Array.isArray(values) ? values : [values]) url.searchParams.append(key, value);
  const operation = path === '/search' ? 'search' : 'metadata';
  const response = await audiusFetch(url, operation, { Accept: 'application/json' }, ctx, deadline);
  if (path !== '/search' && [404, 410].includes(response.status)) {
    await response.body?.cancel();
    return missingTrack;
  }
  if (!response.ok) {
    await response.body?.cancel();
    throw providerHttpFailure('AUDIUS', operation, response.status, response.headers.get('retry-after'), ctx);
  }
  const payload = await response.json().catch(() => { throw invalidProviderResponse('AUDIUS', operation, ctx); });
  if (!payload || typeof payload !== 'object' || !('data' in payload))
    throw invalidProviderResponse('AUDIUS', operation, ctx);
  return payload.data;
}

export async function audiusList(path: '/search' | '/trending', params: Record<string, string | string[]>, ctx?: RequestContext, deadline?: AbortSignal) {
  const data = await audiusJson(path, params, ctx,deadline);
  if (!Array.isArray(data)) throw invalidProviderResponse('AUDIUS', path === '/search' ? 'search' : 'metadata', ctx);
  return data;
}

export async function audiusTrack(id: string, ctx?: RequestContext, deadline?: AbortSignal): Promise<Metadata> {
  if (!trackId.test(id)) return { reason: 'invalid_metadata' };
  const data = await audiusJson('/' + id, {}, ctx, deadline);
  if (data === missingTrack) return { reason: 'missing' };
  const metadata = audiusMetadata(data);
  if ('track' in metadata && metadata.track.id !== id) throw invalidProviderResponse('AUDIUS', 'metadata', ctx);
  return metadata;
}

// Only contact Audius's official stream host and known Audius content-node domains.
function streamLocation(raw: string | null, base: URL): URL | null {
  if (!raw) return null;
  try {
    const url = new URL(raw, base);
    const hosts = (Deno.env.get('AUDIUS_CONTENT_HOSTS') ?? '').split(',').map(host => host.trim());
    const knownHost = /^(creatornode\.audius\.co|audius-nodes\.com|v\.monophonic\.digital|audius-content-\d+\.figment\.io|audius-creator-\d+\.theblueprint\.xyz|val\d+\.open-audio-validator\.com|validator\.eeba4a6ca56a0d87af802270217c2a51\.r2\.cloudflarestorage\.com)$/;
    const key = apiKey();
    if (url.protocol !== 'https:' || url.username || url.password || url.port ||
        (!knownHost.test(url.hostname) && !hosts.includes(url.hostname)) ||
        (key && decodeURIComponent(url.href).includes(key))) return null;
    return url;
  } catch { return null; }
}

function audioSignature(bytes: Uint8Array): boolean {
  const label = new TextDecoder().decode(bytes.subarray(0, 12));
  return bytes.length >= 12 && (label.startsWith('ID3') || label.startsWith('fLaC') ||
    label.startsWith('OggS') || label.slice(4, 8) === 'ftyp' ||
    (bytes[0] === 0xff && (bytes[1] & 0xe0) === 0xe0));
}

async function playableBytes(response: Response): Promise<boolean> {
  const type = response.headers.get('content-type') ?? '';
  if (!/^(audio\/|application\/octet-stream\b)/i.test(type) || !response.body) {
    await response.body?.cancel();
    return false;
  }
  if (response.status === 206 && !/^bytes 0-\d+\/\d+$/i.test(response.headers.get('content-range') ?? '')) {
    await response.body.cancel();
    return false;
  }
  const reader = response.body.getReader();
  const prefix = new Uint8Array(12);
  let read = 0;
  try {
    while (read < prefix.length) {
      const { value, done } = await reader.read();
      if (done) return false;
      const part = value.subarray(0, prefix.length - read);
      prefix.set(part, read);
      read += part.length;
    }
    return audioSignature(prefix);
  } finally { await reader.cancel().catch(() => {}); }
}

function streamExpiry(url: URL): string | null {
  const signed = url.searchParams.get('X-Amz-Date');
  const seconds = Number(url.searchParams.get('X-Amz-Expires'));
  const parts = signed?.match(/^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/);
  if (!parts || !Number.isSafeInteger(seconds) || seconds < 1 || seconds > 604800) return null;
  const [, year, month, day, hours, minutes, secs] = parts;
  const begins = Date.parse(year + '-' + month + '-' + day + 'T' + hours + ':' + minutes + ':' + secs + 'Z');
  return Number.isFinite(begins) ? new Date(begins + seconds * 1000).toISOString() : null;
}

/** Resolve the official stream endpoint, then probe its public target without forwarding credentials. */
export async function probeAudiusStream(
  track: AudiusTrack, ctx?: RequestContext, deadline?: AbortSignal,
): Promise<Stream> {
  if (!trackId.test(track.id)) return { reason: 'invalid_metadata' };
  let url = new URL(apiOrigin + '/v1/tracks/' + track.id + '/stream');
  const range = { Range: 'bytes=0-1023', Accept: 'audio/*, application/octet-stream' };
  const timeout = AbortSignal.timeout(12000);
  const signal = deadline ? AbortSignal.any([timeout, deadline]) : timeout;
  let response = await audiusFetch(url, 'stream', range, ctx, deadline);
  let authenticatedRequest = !!apiKey();
  for (let hop = 0; ; hop++) {
    if (redirects.includes(response.status)) {
      const target = streamLocation(response.headers.get('location'), url);
      await response.body?.cancel();
      if (!target || hop >= 3) throw invalidProviderResponse('AUDIUS', 'stream', ctx);
      url = target;
      authenticatedRequest = false;
      response = await audiusFetch(url,'stream',range,ctx,signal,false);
      continue;
    }
    if ([200, 206].includes(response.status)) {
      if (authenticatedRequest) {
        // A direct authenticated response does not establish anonymous playback.
        await response.body?.cancel();
        authenticatedRequest = false;
        response = await audiusFetch(url,'stream',range,ctx,signal,false);
        continue;
      }
      const valid = await playableBytes(response)
        .catch(e => { throw networkFailure('AUDIUS', 'stream', e, ctx); });
      return valid ? { url: url.href, expiresAt: streamExpiry(url) } : { reason: 'invalid_audio' };
    }
    await response.body?.cancel();
    if ([401, 402, 403, 451].includes(response.status)) return { reason: 'restricted' };
    if ([404, 410].includes(response.status)) return { reason: 'missing_stream' };
    throw providerHttpFailure('AUDIUS', 'stream', response.status, response.headers.get('retry-after'), ctx);
  }
}

/** Check a stream for catalog decisions, retaining the previous verdict when the reply is malformed. */
export async function checkAudiusStream(
  track: AudiusTrack, restricted: boolean, ctx?: RequestContext, deadline?: AbortSignal,
): Promise<Stream> {
  if (restricted) return { reason: 'restricted' };
  const result = await probeAudiusStream(track, ctx, deadline);
  if ('reason' in result && ['invalid_audio', 'invalid_metadata'].includes(result.reason))
    throw invalidProviderResponse('AUDIUS', 'stream', ctx);
  return result;
}
