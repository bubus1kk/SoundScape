import { authenticate, body, reserve, response, run, select, uuid, rpc, HttpError } from '../_shared/core.ts';
import { audiusTrack, checkAudiusStream } from '../_shared/audius.ts';
import { invalidProviderResponse } from '../_shared/provider_errors.ts';
import { verifiedExternalSource } from '../_shared/catalog.ts';
import type { RequestContext } from '../_shared/observability.ts';

export async function resolveTrack(trackId: string, ctx?: RequestContext) {
  const tracks = await select('tracks', 'select=id&id=eq.' + trackId + '&limit=1', null, true, ctx);
  if (!tracks.length) throw new HttpError(404, 'NOT_FOUND', 'Track not found');
  const sources = await select('track_sources',
    'select=provider,provider_track_id,external_url,can_stream,verified_at&track_id=eq.' + trackId +
    '&order=can_stream.desc,provider.asc,provider_track_id.asc', null, true, ctx);

  let upstreamFailure: HttpError | undefined;
  const external: { url: string; provider: string }[] = [];
  for (const source of sources) {
    if (source.provider !== 'AUDIUS') {
      if (source.verified_at && verifiedExternalSource(source))
        external.push({ url: source.external_url, provider: source.provider });
      continue;
    }
    let metadata;
    let stream;
    try {
      metadata = await audiusTrack(source.provider_track_id, ctx);
      if ('track' in metadata) stream = await checkAudiusStream(metadata.track, metadata.restricted, ctx);
      else if (metadata.reason === 'invalid_metadata') throw invalidProviderResponse('AUDIUS', 'metadata', ctx);
    } catch (error) {
      if (!(error instanceof HttpError)) throw error;
      upstreamFailure = error;
      continue;
    }
    const mark = (allowed: boolean | null) => rpc('edge_mark_streamable', {
      p_track: trackId, p_provider_id: source.provider_track_id, p_allowed: allowed,
    }, ctx);

    if (stream && 'url' in stream) {
      await mark(true);
      return { trackId, availability: 'IN_APP', provider: 'AUDIUS', streamUrl: stream.url,
        streamExpiresAt: stream.expiresAt, externalUrl: null, cacheAllowed: false, downloadAllowed: false };
    }
    if ('track' in metadata && stream?.reason === 'restricted') {
      await mark(false);
      external.push({ url: metadata.track.permalink, provider: 'AUDIUS' });
    } else if (('reason' in metadata && ['missing', 'deleted', 'non_public', 'unavailable'].includes(metadata.reason)) ||
        stream?.reason === 'missing_stream') {
      await mark(null);
    }
  }
  if (external.length) {
    return { trackId, availability: 'EXTERNAL_ONLY', provider: external[0].provider, streamUrl: null,
      streamExpiresAt: null, externalUrl: external[0].url, cacheAllowed: false, downloadAllowed: false };
  }
  if (upstreamFailure) throw upstreamFailure;
  throw new HttpError(404, 'TRACK_UNAVAILABLE', 'Track has no currently available playable source');
}

export default { fetch: run(async (req, ctx) => {
  const session = await authenticate(req);
  const p = await body(req);
  const trackId = uuid(p.trackId, 'trackId');
  await reserve('RESOLVE', session.userId, ctx);
  return response(await resolveTrack(trackId, ctx));
}, 'v1-resolve-track') };
