import { HttpError, select, rpc, isSafeExternalUrl, sha256 } from './core.ts';
import { invalidProviderResponse } from './provider_errors.ts';
import { audiusList, audiusMetadata, checkAudiusStream } from './audius.ts';
import type { AudiusTrack } from './audius.ts';
import type { RequestContext } from './observability.ts';
import { candidateRelevance } from './ai_query.ts';
import type { MusicContext } from './ai_query.ts';
export type Track = {id:string;title:string;artist:string;durationMs:number;availability:'IN_APP'|'EXTERNAL_ONLY';provider?:string};
export async function trackViews(ids:string[],ctx?:RequestContext):Promise<Track[]> {
 if(!ids.length)return [];
 const [ts,as,ta,sources]=await Promise.all([
  select('tracks','select=id,title,duration_ms&id=in.('+ids.join(',')+')',null,true,ctx),
  select('artists','select=id,name',null,true,ctx),
  select('track_artists','select=track_id,artist_id,credit_order&track_id=in.('+ids.join(',')+')',null,true,ctx),
  select('track_sources','select=track_id,provider,can_stream,external_url,verified_at&track_id=in.('+ids.join(',')+')',null,true,ctx)
 ]);
 const artists=new Map(as.map(a=>[a.id,a.name]));
 return ts.flatMap(t=>{
  const candidate=sources.filter(s=>s.track_id===t.id&&s.verified_at&&(s.can_stream||isSafeExternalUrl(s.external_url)));
  if(!candidate.length)return [];
  const inApp=candidate.some(s=>s.can_stream);
  const provider=candidate.find(s=>s.can_stream)||candidate[0];
  return [{id:t.id,title:t.title,artist:ta.filter(a=>a.track_id===t.id).sort((a,b)=>a.credit_order-b.credit_order).map(a=>artists.get(a.artist_id)).filter(Boolean).join(', ')||'Unknown artist',durationMs:t.duration_ms,availability:inApp?'IN_APP':'EXTERNAL_ONLY',provider:provider.provider} as Track];
 });
}
/** A valid empty Audius list is different from a malformed upstream envelope. */
export function parseAudiusSearchResponse(payload: unknown, ctx?: RequestContext): AudiusTrack[] {
  if (!payload || typeof payload !== 'object' || !('data' in payload) || !Array.isArray(payload.data))
    throw invalidProviderResponse('AUDIUS', 'search', ctx);
  return payload.data.flatMap(raw => {
    const result = audiusMetadata(raw);
    return 'track' in result && !result.restricted ? [result.track] : [];
  });
}

type SearchFilters = { genreIds: number[]; moodIds: number[]; onlyInApp: boolean };
const remoteSearchLimit = 60;
const verificationAgeMs = 60 * 60 * 1000;
const parallelStreamChecks = 3;
const providerErrors = ['RATE_LIMITED', 'PROVIDER_UNAVAILABLE', 'PROVIDER_ERROR', 'PROVIDER_INVALID_RESPONSE'];

async function facetNames(table: 'genres' | 'moods', ids: number[], ctx?: RequestContext): Promise<string[]> {
  if (!ids.length) return [];
  const rows = await select(table, 'select=name&id=in.(' + ids.join(',') + ')', null, true, ctx);
  return rows.map(row => row.name);
}

async function liveTrackIds(query: string, filters: SearchFilters, ctx?: RequestContext): Promise<string[]> {
  const deadline = AbortSignal.timeout(21000);
  const [genres, moods] = await Promise.all([
    facetNames('genres', filters.genreIds, ctx), facetNames('moods', filters.moodIds, ctx),
  ]);
  if ((filters.genreIds.length && !genres.length) || (filters.moodIds.length && !moods.length)) return [];
  const params: Record<string, string | string[]> = { query, limit: String(remoteSearchLimit) };
  if (genres.length) params.genre = genres;
  if (moods.length) params.mood = moods;
  const rows = await audiusList('/search', params, ctx);
  const candidates = new Map<string, { track: AudiusTrack; restricted: boolean }>();
  const matches = (names: string[], name?: string) =>
    !names.length || names.some(value => value.toLowerCase() === name?.toLowerCase());
  for (const raw of rows.slice(0, remoteSearchLimit)) {
    const item = audiusMetadata(raw);
    if ('track' in item && matches(genres, item.track.genre) && matches(moods, item.track.mood))
      candidates.set(item.track.id, item);
  }
  if (!candidates.size) return [];

  const old = await select('track_sources',
    'select=provider_track_id,track_id,can_stream,verified_at&provider=eq.AUDIUS&provider_track_id=in.(' +
    [...candidates.keys()].join(',') + ')', null, true, ctx);
  const existing = new Map(old.map(source => [source.provider_track_id, source]));
  const result: string[] = [];
  const pending: { track: AudiusTrack; restricted: boolean }[] = [];
  for (const item of candidates.values()) {
    const source = existing.get(item.track.id);
    if (!item.restricted && source?.can_stream && Date.now() - Date.parse(source.verified_at) < verificationAgeMs) {
      result.push(source.track_id);
    } else if (item.restricted && source?.can_stream === false && source.verified_at) {
      result.push(source.track_id);
    } else {
      pending.push(item);
    }
  }

  let upstreamFailure: HttpError | undefined;
  for (let offset = 0; offset < pending.length && !deadline.aborted; offset += parallelStreamChecks) {
    const batch = await Promise.all(pending.slice(offset, offset + parallelStreamChecks).map(async ({ track, restricted }) => {
      const source = existing.get(track.id);
      try {
        const stream = await checkAudiusStream(track, restricted, ctx, deadline);
        if ('url' in stream) return { ...track, canStream: true };
        if (stream.reason === 'restricted') return { ...track, canStream: false };
        if (source && stream.reason === 'missing_stream')
          await rpc('edge_mark_streamable', { p_track: source.track_id, p_provider_id: track.id, p_allowed: null }, ctx);
        return null;
      } catch (error) {
        if (!(error instanceof HttpError) || !providerErrors.includes(error.code)) throw error;
        if (!upstreamFailure || error.status === 429) upstreamFailure = error;
        return null;
      }
    }));
    const items = batch.filter(item => item !== null);
    if (items.length) {
      const imported = await rpc('edge_import_audius_batch', { p_items: items }, ctx);
      result.push(...imported.map((row: { id: string }) => row.id));
    }
    if (upstreamFailure?.status === 429) break;
  }
  if (!result.length && upstreamFailure) throw upstreamFailure;
  return result;
}

/** Page one merged result set even when a later Audius call would return different candidates. */
export async function searchCatalog(
  query: string, filters: SearchFilters, offset: number, limit: number, ctx?: RequestContext,
): Promise<Track[]> {
  const hash = await sha256(JSON.stringify([
    query, [...filters.genreIds].sort(), [...filters.moodIds].sort(), filters.onlyInApp,
  ]));
  const page = { p_hash: hash, p_offset: offset, p_limit: limit };
  const cached = await rpc('edge_catalog_search_page', page, ctx);
  if (Array.isArray(cached)) return cached;
  if (cached !== null) throw new HttpError(502, 'DATABASE_RESPONSE_INVALID', 'Invalid catalog page');
  let liveIds: string[] = [];
  let upstreamFailure: HttpError | undefined;
  try { liveIds = await liveTrackIds(query, filters, ctx); }
  catch (error) {
    if (!(error instanceof HttpError) || !providerErrors.includes(error.code)) throw error;
    upstreamFailure = error;
  }
  const tracks = await rpc('edge_search_catalog', {
    p_query: query, p_genres: filters.genreIds, p_moods: filters.moodIds,
    p_only_in_app: filters.onlyInApp, p_live_ids: liveIds, p_offset: 0, p_limit: 1051,
  }, ctx);
  if (!Array.isArray(tracks)) throw new HttpError(502, 'DATABASE_RESPONSE_INVALID', 'Invalid catalog result');
  if (!tracks.length && upstreamFailure) throw upstreamFailure;
  const persisted = await rpc('edge_catalog_search_page', { ...page, p_items: tracks }, ctx);
  if (!Array.isArray(persisted)) throw new HttpError(502, 'DATABASE_RESPONSE_INVALID', 'Invalid catalog page');
  return persisted;
}

type GenerationSource = {
  provider: string; can_stream: boolean; external_url: string | null; verified_at: string | null;
};
type GenerationRow = {
  id: string; title: string; duration_ms: number;
  track_artists: { credit_order: number; artists: { name: string } | null }[];
  track_genres: { genre_id: number; genres: { name: string } | null }[];
  track_moods: { mood_id: number; moods: { name: string } | null }[];
  track_sources: GenerationSource[];
  playlist_tracks: { position: number; playlists: (MusicContext & {
    is_editorial: boolean; visibility: string; published_at: string | null;
    playlist_genres: { genres: { name: string } | null }[];
  }) | null }[];
};
export type GenerationSearch = {
  prompt: string; genre: string; mood: string; context: readonly MusicContext[];
  pinnedTrackIds: readonly string[]; onlyInApp: boolean; genreIds: number[]; moodIds: number[];
};

export function verifiedExternalSource(source: GenerationSource): boolean {
  if (source.provider === 'AUDIUS') return isSafeExternalUrl(source.external_url);
  if (!source.external_url) return false;
  try {
    const url = new URL(source.external_url);
    if (url.protocol !== 'https:' || url.username || url.password || url.port || url.pathname === '/') return false;
    return source.provider === 'APPLE_MUSIC' && url.hostname === 'music.apple.com' ||
      source.provider === 'BANDCAMP' && /^[a-z0-9-]+\.bandcamp\.com$/.test(url.hostname);
  } catch {
    return false;
  }
}

const generationFields = [
  'id,title,duration_ms', 'track_artists(credit_order,artists(name))',
  'track_genres(genre_id,genres(name))', 'track_moods(mood_id,moods(name))',
  'track_sources(provider,can_stream,external_url,verified_at)',
  'playlist_tracks(position,playlists(title,description,is_editorial,visibility,published_at,playlist_genres(genres(name))))',
].join(',');

/** Rank verified catalog tracks, including Audius imports, by availability and context. */
export async function findGenerationCandidates(
  request: GenerationSearch, count: number, ctx?: RequestContext, read = select,
): Promise<Track[]> {
  const relevance = candidateRelevance(request.prompt, request);
  const pinned = new Set(request.pinnedTrackIds);
  const hasFilters = request.genreIds.length > 0 || request.moodIds.length > 0;
  const filtersOnly = hasFilters && !request.prompt && !request.genre && !request.mood && !request.context.length;
  const ranked: { track: Track; score: number; position: number }[] = [];
  const pageSize = 100;
  let after = '';
  while (true) {
    const rows: GenerationRow[] = await read('tracks',
      `select=${generationFields}&order=id.asc&limit=${pageSize}${after ? '&id=gt.' + encodeURIComponent(after) : ''}`,
      null, true, ctx);
    for (const row of rows) {
      if (request.genreIds.length && !row.track_genres.some(link => request.genreIds.includes(link.genre_id))) continue;
      if (request.moodIds.length && !row.track_moods.some(link => request.moodIds.includes(link.mood_id))) continue;
      const sources = row.track_sources.filter(source => source.verified_at);
      const stream = sources.find(source => source.provider === 'AUDIUS' && source.can_stream);
      const source = stream ?? (!request.onlyInApp ? sources.find(verifiedExternalSource) : undefined);
      if (!source) continue;
      const artist = row.track_artists.sort((a, b) => a.credit_order - b.credit_order)
        .map(credit => credit.artists?.name).filter(Boolean).join(', ');
      let editorial = 0;
      let position = Number.MAX_SAFE_INTEGER;
      for (const link of row.playlist_tracks) {
        const playlist = link.playlists;
        if (!playlist?.is_editorial || playlist.visibility !== 'PUBLIC' || !playlist.published_at) continue;
        const score = relevance.editorial(playlist,
          playlist.playlist_genres.flatMap(link => link.genres ? [link.genres.name] : []));
        if (score > editorial || (score > 0 && score === editorial && link.position < position)) {
          editorial = score;
          position = link.position;
        }
      }
      const score = relevance.track({
        title: row.title, artist,
        genres: row.track_genres.flatMap(link => link.genres ? [link.genres.name] : []),
        moods: row.track_moods.flatMap(link => link.moods ? [link.moods.name] : []),
      }) + editorial + (pinned.has(row.id) ? 20 : 0);
      if (!score && !filtersOnly) continue;
      ranked.push({ score, position, track: {
        id: row.id, title: row.title, artist: artist || 'Unknown artist', durationMs: row.duration_ms,
        availability: stream ? 'IN_APP' : 'EXTERNAL_ONLY', provider: source.provider,
      } });
    }
    if (rows.length < pageSize) break;
    after = rows[rows.length - 1].id;
  }
  return ranked.sort((a, b) => Number(b.track.availability === 'IN_APP') - Number(a.track.availability === 'IN_APP') ||
    b.score - a.score || a.position - b.position || a.track.id.localeCompare(b.track.id))
    .slice(0, count).map(({ track }) => track);
}
// MusicBrainz is metadata-only: an MBID alone never constitutes a stream/external playable link.
export async function musicBrainzRecording(query:string):Promise<{id:string;title:string}[]> {
 const ua=Deno.env.get('MUSICBRAINZ_USER_AGENT');
 if(!ua)return []; // do not send anonymous User-Agent
 const u=new URL('https://musicbrainz.org/ws/2/recording/');u.searchParams.set('query',query);u.searchParams.set('fmt','json');u.searchParams.set('limit','5');
 const r=await fetch(u,{headers:{'user-agent':ua,accept:'application/json'},signal:AbortSignal.timeout(6000)}).catch(()=>null);
 if(!r?.ok)return [];
 const j=await r.json().catch(()=>({}));return (Array.isArray(j.recordings)?j.recordings:[]).filter((x:any)=>typeof x.id==='string'&&typeof x.title==='string').map((x:any)=>({id:x.id,title:x.title}));
}
