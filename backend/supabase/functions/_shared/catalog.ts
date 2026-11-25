import { HttpError, select, rpc, isSafeExternalUrl, sha256 } from './core.ts';
import { invalidProviderResponse } from './provider_errors.ts';
import { audiusList, audiusMetadata, audiusOperationLimits, audiusTrack, checkAudiusStream, withAudiusProbe } from './audius.ts';
import type { AudiusTrack } from './audius.ts';
import type { RequestContext } from './observability.ts';
import { candidateRelevance } from './ai_query.ts';
import type { MusicContext } from './ai_query.ts';
import { intentRelevance, type MusicIntent } from './music_intent.ts';
export type Track = {id:string;title:string;artist:string;durationMs:number;availability:'IN_APP'|'EXTERNAL_ONLY';provider?:string;genres?:string[];moods?:string[]};
export async function trackViews(ids:string[],ctx?:RequestContext,deadline?:AbortSignal):Promise<Track[]> {
 if(!ids.length)return [];
 const rows=await select('tracks','select=id,title,duration_ms,track_artists(credit_order,artists(name)),'+
  'track_sources(provider,can_stream,external_url,verified_at)&id=in.('+ids.join(',')+')',null,true,ctx,deadline) as GenerationRow[];
 const now=Date.now();
 return rows.flatMap(t=>{
  const stream=t.track_sources.find(source=>freshAudiusStream(source,now));
  const source=stream??t.track_sources.find(externalCatalogSource);
  if(!source)return [];
  const artist=t.track_artists.sort((a,b)=>a.credit_order-b.credit_order)
   .map(credit=>credit.artists?.name).filter(Boolean).join(', ')||'Unknown artist';
  return [{id:t.id,title:t.title,artist,durationMs:t.duration_ms,availability:stream?'IN_APP':'EXTERNAL_ONLY',provider:source.provider}];
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
const providerErrors = ['RATE_LIMITED', 'PROVIDER_UNAVAILABLE', 'PROVIDER_ERROR', 'PROVIDER_INVALID_RESPONSE'];
type VerificationBudget = { remaining:number; parallel:number; attempted:Set<string>; rateLimited:boolean };
const newVerificationBudget = (ctx?:RequestContext):VerificationBudget => {
  const {updates,parallel}=audiusOperationLimits(ctx);
  return {remaining:updates,parallel,attempted:new Set(),rateLimited:false};
};

export function freshAudiusStream(source: Pick<GenerationSource, 'provider'|'can_stream'|'verified_at'>, now = Date.now()) {
  return source.provider === 'AUDIUS' && source.can_stream && source.verified_at !== null &&
    Date.parse(source.verified_at) > now - verificationAgeMs;
}

async function facetNames(table: 'genres' | 'moods', ids: number[], ctx?: RequestContext, deadline?:AbortSignal): Promise<string[]> {
  if (!ids.length) return [];
  const rows = await select(table, 'select=name&id=in.(' + ids.join(',') + ')', null, true, ctx,deadline);
  return rows.map(row => row.name);
}

async function liveTrackIds(query: string, filters: SearchFilters, ctx?: RequestContext, budget?: AbortSignal,
  checks = newVerificationBudget(ctx)): Promise<string[]> {
  const timeout = AbortSignal.timeout(21000);
  const deadline = budget ? AbortSignal.any([timeout,budget]) : timeout;
  const [genres, moods] = await Promise.all([
    facetNames('genres', filters.genreIds, ctx,deadline), facetNames('moods', filters.moodIds, ctx,deadline),
  ]);
  if ((filters.genreIds.length && !genres.length) || (filters.moodIds.length && !moods.length)) return [];
  const params: Record<string, string | string[]> = { query, limit: String(remoteSearchLimit) };
  if (genres.length) params.genre = genres;
  if (moods.length) params.mood = moods;
  const rows = await audiusList('/search', params, ctx,deadline);
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
    [...candidates.keys()].join(',') + ')', null, true, ctx,deadline);
  const existing = new Map(old.map(source => [source.provider_track_id, source]));
  const result: string[] = [];
  const pending: { track: AudiusTrack; restricted: boolean }[] = [];
  for (const item of candidates.values()) {
    const source = existing.get(item.track.id);
    if (!item.restricted && source && freshAudiusStream({ ...source, provider: 'AUDIUS' })) {
      result.push(source.track_id);
    } else if (item.restricted && source?.can_stream === false && source.verified_at) {
      result.push(source.track_id);
    } else {
      pending.push(item);
    }
  }

  let upstreamFailure: HttpError | undefined;
  for (let offset = 0; offset < pending.length && checks.remaining && !deadline.aborted; offset += checks.parallel) {
    const next = pending.slice(offset,offset+Math.min(checks.parallel,checks.remaining));
    checks.remaining-=next.length;
    const batch = await Promise.all(next.map(async ({ track, restricted }) => {
      checks.attempted.add(track.id);
      const source = existing.get(track.id);
      try {
        return await withAudiusProbe(track.id,async()=>{
          const stream = await checkAudiusStream(track, restricted, ctx, deadline);
          if ('url' in stream || stream.reason === 'restricted') {
            const imported=await rpc('edge_import_audius_batch',{
              p_items:[{...track,canStream:'url' in stream}],
            },ctx,deadline);
            return imported[0].id as string;
          }
          if (source && stream.reason === 'missing_stream')
            await rpc('edge_mark_streamable', { p_track: source.track_id, p_provider_id: track.id, p_allowed: null }, ctx,deadline);
          return null;
        },ctx,deadline);
      } catch (error) {
        if (!(error instanceof HttpError) || !providerErrors.includes(error.code)) throw error;
        if (!upstreamFailure || error.status === 429) upstreamFailure = error;
        return null;
      }
    }));
    result.push(...batch.filter(item => item !== null));
    if (upstreamFailure?.status === 429) {
      checks.rateLimited=true;
      break;
    }
  }
  if (!result.length && upstreamFailure) throw upstreamFailure;
  return result;
}

/** Page one merged result set even when a later Audius call would return different candidates. */
export async function searchCatalog(
  query: string, filters: SearchFilters, offset: number, limit: number, ctx?: RequestContext,
): Promise<Track[]> {
  const hash = await sha256(JSON.stringify([
    2, query, [...filters.genreIds].sort(), [...filters.moodIds].sort(), filters.onlyInApp,
  ]));
  const page = { p_hash: hash, p_offset: offset, p_limit: limit };
  const cached = await rpc('edge_catalog_search_page', page, ctx);
  if (Array.isArray(cached)) return cached;
  if (cached !== null) throw new HttpError(502, 'DATABASE_RESPONSE_INVALID', 'Invalid catalog page');
  let liveIds: string[] = [];
  let upstreamFailure: HttpError | undefined;
  const checks=newVerificationBudget(ctx);
  const deadline=AbortSignal.timeout(21000);
  try { liveIds = await liveTrackIds(query, filters, ctx,deadline,checks); }
  catch (error) {
    if (!(error instanceof HttpError) || !providerErrors.includes(error.code)) throw error;
    upstreamFailure = error;
  }
  if(filters.onlyInApp&&checks.remaining&&!checks.rateLimited&&!upstreamFailure&&!deadline.aborted) {
    const expired = await rpc('edge_expired_audius_sources',{
      p_query:query,p_genres:filters.genreIds,p_moods:filters.moodIds,p_limit:checks.remaining,
    },ctx) as {track_id:string;provider_track_id:string}[];
    await refreshExpired(expired,checks,deadline,ctx);
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
  provider_track_id?: string;
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
  intent?: MusicIntent;
  deadline?: AbortSignal;
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

export function externalCatalogSource(source:GenerationSource) {
  return Boolean(source.verified_at) && !(source.provider==='AUDIUS'&&source.can_stream) && verifiedExternalSource(source);
}

const generationFields = [
  'id,title,duration_ms', 'track_artists(credit_order,artists(name))',
  'track_genres(genre_id,genres(name))', 'track_moods(mood_id,moods(name))',
  'track_sources(provider,provider_track_id,can_stream,external_url,verified_at)',
  'playlist_tracks(position,playlists(title,description,is_editorial,visibility,published_at,playlist_genres(genres(name))))',
].join(',');

async function refreshSource(trackId:string, providerId:string, deadline: AbortSignal, ctx?: RequestContext) {
  if (deadline.aborted) return false;
  try {
    return await withAudiusProbe(providerId, async () => {
      const metadata = await audiusTrack(providerId, ctx, deadline);
      const stream = 'track' in metadata
        ? await checkAudiusStream(metadata.track, metadata.restricted, ctx, deadline) : undefined;
      const decision = stream && 'url' in stream ? true
        : stream?.reason === 'restricted' ? false
        : (stream?.reason === 'missing_stream' || ('reason' in metadata &&
          ['missing', 'deleted', 'non_public', 'unavailable'].includes(metadata.reason))) ? null : undefined;
      if (decision === undefined) return false;
      await rpc('edge_mark_streamable', {p_track:trackId,p_provider_id:providerId,p_allowed:decision}, ctx, deadline);
      return decision === true;
    }, ctx, deadline);
  } catch (error) {
    if (error instanceof HttpError && providerErrors.includes(error.code)) {
      if (error.status === 429) throw error;
      return false;
    }
    throw error;
  }
}

async function refreshExpired(sources:{track_id:string;provider_track_id:string}[],checks:VerificationBudget,
  deadline:AbortSignal,ctx?:RequestContext):Promise<Set<string>> {
  const confirmed=new Set<string>();
  const pending=sources.filter(source=>!checks.attempted.has(source.provider_track_id)).slice(0,checks.remaining);
  for(let offset=0;offset<pending.length&&!checks.rateLimited&&!deadline.aborted;offset+=checks.parallel){
    await Promise.all(pending.slice(offset,offset+checks.parallel).map(async source=>{
      checks.attempted.add(source.provider_track_id);
      checks.remaining--;
      try {
        if(await refreshSource(source.track_id,source.provider_track_id,deadline,ctx))confirmed.add(source.track_id);
      } catch(error) {
        if(error instanceof HttpError&&error.status===429)checks.rateLimited=true;
        else throw error;
      }
    }));
  }
  return confirmed;
}

/** Rank verified catalog tracks, including Audius imports, by availability and context. */
export async function findGenerationCandidates(
  request: GenerationSearch, count: number, ctx?: RequestContext, read = select,
): Promise<Track[]> {
  const relevance = candidateRelevance(request.prompt, request);
  const pinned = new Set(request.pinnedTrackIds);
  const hasFilters = request.genreIds.length > 0 || request.moodIds.length > 0;
  const filtersOnly = hasFilters && !request.prompt && !request.genre && !request.mood && !request.context.length;
  type Ranked = { track: Track; score: number; position: number };
  const ranked: Ranked[] = [];
  const stale: (Ranked & { source: GenerationSource })[] = [];
  const checks=newVerificationBudget(ctx);
  const pageSize = 100;
  let after = '';
  let semanticOffset = 0;
  let semanticIds: string[] | undefined;
  if (request.intent) {
    const intent = request.intent;
    const labels = (names:string[])=>names.map(name=>name.trim().toLowerCase());
    const retrieve = async () => {
      const ids = await rpc('edge_generation_track_ids',{
        p_genres:labels(intent.genres),p_moods:labels(intent.moods),p_excluded:labels(intent.excludedGenres),
        p_genre_ids:request.genreIds,p_mood_ids:request.moodIds,p_kind:intent.kind,
        p_target:intent.target.toLowerCase(),p_context_titles:labels(request.context.map(stop=>stop.title)),
        p_pinned:request.pinnedTrackIds.slice(0,240),p_limit:240,p_only_in_app:request.onlyInApp,
      },ctx,request.deadline);
      if (!Array.isArray(ids)) throw new HttpError(502,'DATABASE_RESPONSE_INVALID','Invalid generation candidate result');
      return ids.map((row:{id:string})=>row.id);
    };
    semanticIds = await retrieve();
    const query = intent.target || intent.genres[0] || intent.moods[0];
    if (semanticIds.length < Math.min(Math.ceil(count/2),5) && query && !request.deadline?.aborted) {
      const clock = AbortSignal.timeout(4500);
      const budget = request.deadline ? AbortSignal.any([clock,request.deadline]) : clock;
      try {
        const live = await liveTrackIds(query,request,ctx,budget,checks);
        if (live.length) semanticIds = await retrieve();
      } catch (error) {
        if (!(error instanceof HttpError) || !providerErrors.includes(error.code) || !semanticIds.length) throw error;
      }
    }
    if (!semanticIds.length) return [];
  }
  while (true) {
    const ids=semanticIds?.slice(semanticOffset,semanticOffset+pageSize);
    const rows: GenerationRow[] = await read('tracks',
      `select=${generationFields}&order=id.asc&limit=${pageSize}` +
        '&playlist_tracks.playlists.is_editorial=eq.true&playlist_tracks.playlists.visibility=eq.PUBLIC' +
        '&playlist_tracks.playlists.published_at=not.is.null' +
        (ids ? '&id=in.('+ids.join(',')+')' : '') + (after ? '&id=gt.' + encodeURIComponent(after) : ''),
      null, true, ctx,request.deadline);
    for (const row of rows) {
      if (request.genreIds.length && !row.track_genres.some(link => request.genreIds.includes(link.genre_id))) continue;
      if (request.moodIds.length && !row.track_moods.some(link => request.moodIds.includes(link.mood_id))) continue;
      const sources = row.track_sources.filter(source => source.verified_at);
      const stream = sources.find(source => freshAudiusStream(source));
      const expired = !stream && request.onlyInApp ? sources.find(source =>
        source.provider === 'AUDIUS' && source.can_stream && source.provider_track_id) : undefined;
      const source = stream ?? (!request.onlyInApp ? sources.find(externalCatalogSource) : expired);
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
      const genres = row.track_genres.flatMap(link => link.genres ? [link.genres.name] : []);
      const moods = row.track_moods.flatMap(link => link.moods ? [link.moods.name] : []);
      const evidence = {title:row.title,artist,genres,moods};
      const musical = request.intent ? intentRelevance(request.intent,evidence) : relevance.track(evidence);
      if (musical < 0) continue;
      const score = (request.intent ? musical * 10 : musical) + editorial + (pinned.has(row.id) ? 20 : 0);
      if (!score && !filtersOnly) continue;
      const candidate: Ranked = { score, position, track: {
        id: row.id, title: row.title, artist: artist || 'Unknown artist', durationMs: row.duration_ms,
        availability: stream || expired ? 'IN_APP' : 'EXTERNAL_ONLY', provider: source.provider, genres, moods,
      } };
      if (expired) stale.push({...candidate, source:expired});
      else ranked.push(candidate);
    }
    if(semanticIds){
      semanticOffset+=pageSize;
      if(semanticOffset>=semanticIds.length)break;
      continue;
    }
    if(rows.length<pageSize)break;
    after = rows[rows.length - 1].id;
  }
  const compare = (a: Ranked,b: Ranked) => (request.intent ? b.score-a.score : 0) ||
    Number(b.track.availability === 'IN_APP') - Number(a.track.availability === 'IN_APP') ||
    b.score - a.score || a.position - b.position || a.track.id.localeCompare(b.track.id);
  if (stale.length&&checks.remaining&&!checks.rateLimited) {
    const timeout=AbortSignal.timeout(4500);
    const deadline=request.deadline?AbortSignal.any([timeout,request.deadline]):timeout;
    const confirmed=await refreshExpired(stale.sort(compare).map(item=>({
      track_id:item.track.id,provider_track_id:item.source.provider_track_id!,
    })),checks,deadline,ctx);
    ranked.push(...stale.filter(item=>confirmed.has(item.track.id)));
  }
  return ranked.sort(compare)
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
