import { audiusMetadata, type AudiusTrack } from '../supabase/functions/_shared/audius.ts';
import { decodeCompletion } from '../supabase/functions/_shared/gigachat_response.ts';
import { editorialCities, editorialModel, editorialPrompt, type CityCode } from './editorial-cities.ts';

const providerId = /^[A-Za-z0-9-]{1,120}$/;
const fixtureId = /^BE03Fixture-[a-z0-9-]+$/;
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const coverPath = /^editorial\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp)$/;
const directAudius = /^https:\/\/(?:www\.)?audius\.co\/[a-zA-Z0-9_-]+\/[a-zA-Z0-9_-]+$/;
const excludedPaths = new Set(['search', 'explore', 'trending', 'playlists', 'albums',
  'tracks', 'settings', 'feed', 'reposts', 'favorites']);
const maxAgeMs = 60 * 60_000;
const batchSize = 20;

type Concept = { atmosphere: string; mood: string; directions: string[]; queries: string[] };
type Evidence = { id: string; verifiedAt: string; permalink: string; streamingAllowed: boolean;
  streamProbePassed: boolean; exactRecording: boolean };
type Candidate = AudiusTrack & { canStream: boolean; verifiedAt: string; query: string;
  relation: 'ATMOSPHERE'; isrc?: string; recordingMbid?: string };
type DraftOptions = { expectedRevision: number; coverPath: string; genreIds: number[]; tagIds: number[] };
type Rejection = { id?: string; reason: string };

export type EditorialManifest = {
  version: 1;
  provenance: 'SYNTHETIC_QA' | 'HISTORICAL';
  city: CityCode;
  title: string;
  description: string;
  concept: Concept;
  model: typeof editorialModel | null;
  candidates: Candidate[];
  selected: string[];
  rejected: Rejection[];
  draft: DraftOptions | null;
};

function object(raw: unknown, label: string): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw Error('Invalid ' + label);
  return raw as Record<string, unknown>;
}

function array(raw: unknown, limit: number, label: string): unknown[] {
  if (!Array.isArray(raw) || raw.length > limit) throw Error('Invalid ' + label);
  return raw;
}

function string(raw: unknown, max: number, label: string): string {
  if (typeof raw !== 'string' || !raw.trim() || raw.length > max ||
      /[\u0000-\u001f\u007f]/.test(raw)) throw Error('Invalid ' + label);
  return raw.trim();
}

function conceptText(raw: unknown, max: number, label: string) {
  const value = string(raw, max, label);
  if (/https?:|www\.|bearer\s|api[_ -]?key|[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}/i.test(value)) {
    throw Error('Invalid ' + label);
  }
  return value;
}

function readConcept(raw: unknown): Concept {
  const value = object(raw, 'concept');
  const list = (key: 'directions' | 'queries') => {
    const result = array(value[key], 4, key).map(item => conceptText(item, 100, key));
    if (!result.length) throw Error('Invalid ' + key);
    return [...new Set(result)];
  };
  if (Object.keys(value).sort().join(',') !== 'atmosphere,directions,mood,queries') throw Error('Invalid concept fields');
  return { atmosphere: conceptText(value.atmosphere, 500, 'atmosphere'),
    mood: conceptText(value.mood, 160, 'mood'), directions: list('directions'), queries: list('queries') };
}

function exactLink(raw: unknown): raw is string {
  return typeof raw === 'string' && directAudius.test(raw) &&
    !raw.split('/').slice(-2).some(part => excludedPaths.has(part.toLowerCase()));
}

function verificationTime(raw: unknown, now: number) {
  const date = string(raw, 32, 'verification time');
  const timestamp = Date.parse(date);
  if (!Number.isFinite(timestamp) || new Date(timestamp).toISOString() !== date || timestamp > now) {
    throw Error('Invalid verification time');
  }
  return date;
}

function readDraft(raw: unknown): DraftOptions | null {
  if (raw == null) return null;
  const value = object(raw, 'draft target');
  const revision = value.expectedRevision;
  const cover = string(value.coverPath, 250, 'cover path');
  if (typeof revision !== 'number' || !Number.isInteger(revision) || revision < 0 || revision > 2147483647 ||
      !coverPath.test(cover)) throw Error('Invalid draft target');
  const facets = (key: 'genreIds' | 'tagIds') => {
    const ids = array(value[key], 30, key);
    if (!ids.length || ids.some(id => typeof id !== 'number' || !Number.isInteger(id) || id < 1 || id > 32767)) {
      throw Error('Invalid ' + key);
    }
    return [...new Set(ids as number[])].sort((a, b) => a - b);
  };
  return { expectedRevision: revision, coverPath: cover, genreIds: facets('genreIds'), tagIds: facets('tagIds') };
}

function findCity(code: unknown) {
  const city = editorialCities.find(item => item.code === code);
  if (!city) throw Error('Unknown editorial city');
  return city;
}

type CaptureProvider = {
  completion: (request: ReturnType<typeof editorialPrompt>) => Promise<unknown>;
  search: (query: string, limit: number) => Promise<unknown[]>;
  verify: (track: AudiusTrack, restricted: boolean) => Promise<Evidence | null>;
};

export async function collectEditorialManifest(
  code: CityCode,
  provenance: EditorialManifest['provenance'],
  provider: CaptureProvider,
  options: { draft?: DraftOptions; selected?: string[]; now?: () => number } = {},
) {
  const completion = await provider.completion(editorialPrompt(code));
  const concept = completionConcept(completion);
  const searches = [];
  const evidence: Evidence[] = [];
  const checked = new Set<string>();
  // Bound candidate verification separately from search results and keep one in-flight call.
  for (const query of concept.queries) {
    const rows = array(await provider.search(query,15),15,'search results');
    searches.push({ query,rows });
    for (const row of rows) {
      const metadata = audiusMetadata(row);
      if ('reason' in metadata || checked.has(metadata.track.id) || checked.size >= 30 ||
          !exactLink(metadata.track.permalink)) continue;
      checked.add(metadata.track.id);
      const proof = await provider.verify(metadata.track,metadata.restricted);
      if (proof?.id === metadata.track.id && proof.permalink === metadata.track.permalink) evidence.push(proof);
    }
  }
  return prepareEditorialManifest(
    { city:code,provenance,completion,searches,evidence,draft:options.draft,selected:options.selected },
    options.now?.() ?? Date.now(),
  );
}

export function completionConcept(completion: unknown): Concept {
  const decoded = decodeCompletion(completion);
  if (decoded.parserFailure || !decoded.observation.actualModel?.match(/^GigaChat-2-Pro(?::[0-9.]+)?$/)) {
    throw Error('Invalid GigaChat-2-Pro completion');
  }
  return readConcept(decoded.value);
}

export function prepareEditorialManifest(raw: unknown, now = Date.now()): EditorialManifest {
  const input = object(raw, 'capture');
  const city = findCity(input.city);
  const provenance = input.provenance;
  if (provenance !== 'SYNTHETIC_QA' && provenance !== 'HISTORICAL') throw Error('Invalid provenance');
  let concept: Concept = {
    atmosphere: city.atmosphere, mood: city.mood, directions: [...city.directions], queries: [...city.queries],
  };
  let model: EditorialManifest['model'] = null;
  if (input.completion !== undefined) {
    concept = completionConcept(input.completion);
    model = editorialModel;
  }
  const evidence = new Map<string, Evidence>();
  for (const row of array(input.evidence, 60, 'verification evidence')) {
    const item = object(row, 'verification evidence');
    const id = string(item.id, 120, 'provider ID');
    if (!providerId.test(id) || evidence.has(id) || !exactLink(item.permalink) ||
        typeof item.streamingAllowed !== 'boolean' || typeof item.streamProbePassed !== 'boolean' ||
        typeof item.exactRecording !== 'boolean') throw Error('Invalid or duplicate verification evidence');
    evidence.set(id,{ id, verifiedAt: verificationTime(item.verifiedAt, now), permalink: item.permalink,
      streamingAllowed: item.streamingAllowed, streamProbePassed: item.streamProbePassed, exactRecording: item.exactRecording });
  }
  const candidates: Candidate[] = [];
  const rejected: Rejection[] = [];
  const seen = new Map<string, Candidate>();
  const conflicts = new Set<string>();
  for (const group of array(input.searches, 4, 'search captures')) {
    const result = object(group, 'search capture');
    const query = conceptText(result.query, 100, 'search query');
    if (!concept.queries.includes(query)) throw Error('Search query is outside the concept');
    for (const row of array(result.rows, 15, 'search results')) {
      const rawTrack = object(row, 'search row');
      const id = typeof rawTrack.id === 'string' && providerId.test(rawTrack.id) ? rawTrack.id : undefined;
      const metadata = audiusMetadata(rawTrack);
      if ('reason' in metadata) { rejected.push({ id, reason: metadata.reason }); continue; }
      const track = metadata.track;
      if (fixtureId.test(track.id) !== (provenance === 'SYNTHETIC_QA')) throw Error('Capture has mixed provenance');
      const proof = evidence.get(track.id);
      const canStream = !!proof && !metadata.restricted && proof.streamingAllowed && proof.streamProbePassed;
      if (!proof || !proof.exactRecording || proof.permalink !== track.permalink || !exactLink(track.permalink)) {
        rejected.push({ id, reason: 'NO_EXACT_SOURCE_EVIDENCE' });
        continue;
      }
      const isrc = typeof rawTrack.isrc === 'string' && /^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$/i.test(rawTrack.isrc)
        ? rawTrack.isrc.toUpperCase() : undefined;
      const recordingMbid = typeof rawTrack.musicbrainz_recording_id === 'string' && uuid.test(rawTrack.musicbrainz_recording_id)
        ? rawTrack.musicbrainz_recording_id.toLowerCase() : undefined;
      const duplicate = seen.get(track.id);
      if (duplicate) {
        if (duplicate.title !== track.title || duplicate.artist !== track.artist || duplicate.artistId !== track.artistId ||
            duplicate.durationMs !== track.durationMs || duplicate.canStream !== canStream ||
            duplicate.isrc !== isrc || duplicate.recordingMbid !== recordingMbid) conflicts.add(track.id);
        rejected.push({ id, reason: conflicts.has(track.id) ? 'CONFLICTING_PROVIDER_IDENTITY' : 'DUPLICATE_PROVIDER_ID' });
        continue;
      }
      const candidate: Candidate = { ...track, canStream, verifiedAt: proof.verifiedAt, query,
        relation: 'ATMOSPHERE', isrc, recordingMbid };
      seen.set(track.id, candidate);
      candidates.push(candidate);
    }
  }
  const eligible = candidates.filter(candidate => !conflicts.has(candidate.id));
  const requested = input.selected === undefined ? eligible.slice(0, 15).map(item => item.id) :
    array(input.selected, 15, 'selected tracks').map(item => string(item,120,'selected track'));
  return validateEditorialManifest({
    version: 1, provenance, city: city.code, title: city.title, description: concept.atmosphere,
    concept, model, candidates: eligible, selected: requested, rejected,
    draft: input.draft ?? null,
  }, now);
}

export function validateEditorialManifest(raw: unknown, now = Date.now()): EditorialManifest {
  const value = object(raw, 'manifest');
  if (value.version !== 1 || (value.provenance !== 'SYNTHETIC_QA' && value.provenance !== 'HISTORICAL') ||
      (value.model !== null && value.model !== editorialModel)) throw Error('Invalid manifest version, model or provenance');
  const provenance = value.provenance;
  const city = findCity(value.city);
  const concept = readConcept(value.concept);
  const ids = new Set<string>();
  const candidates = array(value.candidates, 60, 'candidates').map(rawTrack => {
    const t = object(rawTrack, 'candidate');
    const id = string(t.id, 120, 'provider ID');
    const title = string(t.title, 300, 'track title');
    const artist = string(t.artist, 200, 'artist');
    const artistId = t.artistId === undefined ? undefined : string(t.artistId, 120, 'artist ID');
    const durationMs = t.durationMs;
    const isrc = t.isrc;
    const recordingMbid = t.recordingMbid;
    const query = conceptText(t.query, 100, 'query');
    const verifiedAt = verificationTime(t.verifiedAt, now);
    if (!providerId.test(id) || ids.has(id) || !exactLink(t.permalink) || !concept.queries.includes(query) ||
        (artistId !== undefined && !providerId.test(artistId)) || typeof durationMs !== 'number' ||
        !Number.isInteger(durationMs) || durationMs < 1 || durationMs > 7200000 ||
        t.relation !== 'ATMOSPHERE' || typeof t.canStream !== 'boolean' ||
        (isrc !== undefined && (typeof isrc !== 'string' || !/^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$/.test(isrc))) ||
        (recordingMbid !== undefined && (typeof recordingMbid !== 'string' || !uuid.test(recordingMbid)))) {
      throw Error('Invalid or duplicate candidate');
    }
    if (fixtureId.test(id) !== (provenance === 'SYNTHETIC_QA') || (provenance === 'SYNTHETIC_QA' &&
        (!artistId || !fixtureId.test(artistId) || !title.startsWith('[BE-03 QA] ') ||
        !t.permalink.startsWith('https://audius.co/be03-fixture/')))) throw Error('Invalid fixture provenance');
    ids.add(id);
    return { id,title,artist,artistId,durationMs,permalink:t.permalink,canStream:t.canStream,verifiedAt,query,
      relation: 'ATMOSPHERE' as const, isrc,recordingMbid,
      genre: t.genre === undefined ? undefined : string(t.genre,80,'track genre'),
      mood: t.mood === undefined ? undefined : string(t.mood,80,'track mood') };
  });
  const selected = array(value.selected,15,'selected').map(item => string(item,120,'selected track'));
  if (new Set(selected).size !== selected.length || selected.some(id => !ids.has(id))) {
    throw Error('Selected tracks must be unique candidates');
  }
  const rejected = array(value.rejected,60,'rejected').map(rawRow => {
    const row = object(rawRow,'rejected');
    const id = row.id === undefined ? undefined : string(row.id,120,'rejected ID');
    const reason = string(row.reason,80,'rejection reason');
    if ((id !== undefined && !providerId.test(id)) || !/^[A-Za-z0-9_]+$/.test(reason)) throw Error('Invalid rejection');
    return { id,reason };
  });
  return { version:1, provenance, city:city.code, title:string(value.title,150,'title'),
    description:string(value.description,2000,'description'), concept,model:value.model,
    candidates,selected,rejected,draft:readDraft(value.draft) };
}

export function editorialManifestReport(manifest: EditorialManifest, now = Date.now()) {
  const selected = manifest.candidates.filter(item => manifest.selected.includes(item.id));
  return { manifest, readyForFixtureImport: manifest.provenance === 'SYNTHETIC_QA' &&
    manifest.draft !== null && selected.length >= 12 &&
    selected.every(item => now - Date.parse(item.verifiedAt) < maxAgeMs),
    selectionComplete: selected.length >= 12,
    staleInApp: selected.filter(item => item.canStream && now - Date.parse(item.verifiedAt) >= maxAgeMs).map(item => item.id),
  };
}

export function buildEditorialDraftInput(manifest: EditorialManifest, rawMapping: unknown) {
  const mapping = object(rawMapping,'catalog mapping');
  const placeId = string(mapping.placeId,36,'place UUID');
  const tracks = object(mapping.trackIdsByProvider,'track mapping');
  if (!manifest.draft || manifest.selected.length < 12 || !uuid.test(placeId)) {
    throw Error('Draft metadata, at least twelve candidates and a place UUID are required');
  }
  const trackIds = manifest.selected.map(id => {
    const trackId = string(tracks[id],36,'SoundScape track UUID');
    if (!uuid.test(trackId)) throw Error('Invalid SoundScape track UUID');
    return trackId.toLowerCase();
  });
  if (new Set(trackIds).size !== trackIds.length) throw Error('Provider IDs resolve to duplicate SoundScape UUIDs');
  return { ...manifest.draft,placeId,title:manifest.provenance==='SYNTHETIC_QA' ? '[BE-03 QA] '+manifest.title : manifest.title,
    description:manifest.description,trackIds };
}

export type FixtureTarget = { url: string; marker: string; apiKey: string; serviceKey: string; editorToken: string };

export function isolatedEditorialRpc(target: FixtureTarget) {
  if (!/^http:\/\/127\.0\.0\.1:[1-9][0-9]{3,4}$/.test(target.url) ||
      !uuid.test(target.marker) || !target.apiKey || !target.serviceKey || !target.editorToken) {
    throw Error('Explicit loopback target, fixture marker and staging credentials required');
  }
  return async (name: string, body: unknown, service = false) => {
    if (!/^[a-z_]+$/.test(name)) throw Error('Invalid editorial RPC name');
    const response = await fetch(target.url + '/rest/v1/rpc/' + name, {
      method:'POST',redirect:'error',signal:AbortSignal.timeout(15_000),body:JSON.stringify(body),
      headers: { 'content-type':'application/json', apikey:service ? target.serviceKey : target.apiKey,
        authorization:'Bearer ' + (service ? target.serviceKey : target.editorToken) },
    });
    if (!response.ok) {
      const error = await response.json().catch(() => null);
      const code = typeof error?.message === 'string' && /^[A-Z_]{4,80}$/.test(error.message)
        ? ' ' + error.message : '';
      throw Error(name + ' failed (HTTP ' + response.status + ')' + code);
    }
    return await response.json();
  };
}

export async function applyEditorialFixture(raw: unknown, target: FixtureTarget) {
  const now = Date.now();
  const manifest = validateEditorialManifest(raw, now);
  if (!editorialManifestReport(manifest, now).readyForFixtureImport || !manifest.draft) {
    throw Error('Manifest is not ready for the isolated fixture import');
  }
  const rpc = isolatedEditorialRpc(target);
  const cities = await rpc('list_editorial_cities', {});
  const city = cities.find((entry: { code: string }) => entry.code === manifest.city);
  if (city?.binding !== 'BOUND' || !uuid.test(city.placeId)) throw Error('City has no unique existing place');
  const current = city.draftRevision;
  if (current !== manifest.draft.expectedRevision && current !== manifest.draft.expectedRevision + 1 &&
      !(current === null && manifest.draft.expectedRevision === 0)) throw Error('Draft revision changed');
  const candidates = new Map(manifest.candidates.map(item => [item.id,item]));
  const tracks = manifest.selected.map(id => candidates.get(id)!);
  const byProvider = new Map<string,string>();
  for (let i=0; i<tracks.length; i+=batchSize) {
    const batch = tracks.slice(i,i+batchSize).map(item => ({
      id:item.id,title:item.title,artist:item.artist,artistId:item.artistId,durationMs:item.durationMs,
      permalink:item.permalink,genre:item.genre,mood:item.mood,canStream:item.canStream,verifiedAt:item.verifiedAt,
    }));
    const rows = await rpc('editorial_import_fixture_batch',{ p_target:target.marker,p_items:batch },true);
    for (const row of array(rows,batch.length,'import response')) {
      const item = object(row,'import response item');
      if (typeof item.id !== 'string' || !uuid.test(item.id) || typeof item.provider_track_id !== 'string' ||
          !batch.some(candidate => candidate.id === item.provider_track_id) || byProvider.has(item.provider_track_id)) {
        throw Error('Invalid isolated import response');
      }
      byProvider.set(item.provider_track_id,item.id);
    }
    if (byProvider.size !== i + batch.length) throw Error('Incomplete isolated import response');
  }
  const payload=buildEditorialDraftInput(manifest,{placeId:city.placeId,trackIdsByProvider:Object.fromEntries(byProvider)});
  const draft = await rpc('save_editorial_draft',city.officialPlaylistId ? {
    placeId:city.placeId,expectedRevision:payload.expectedRevision,trackIds:payload.trackIds,
  } : payload);
  return { draft, validation:await rpc('check_editorial_draft', { placeId:city.placeId }),
    importedPositions:byProvider.size, rejected:manifest.rejected, provenance:manifest.provenance };
}

if (import.meta.main) {
  const [mode, path, target, marker, ...extra] = Deno.args;
  const argc = mode === '--apply-fixture' ? 4 : mode === '--draft-payload' ? 3 : 2;
  if (extra.length || Deno.args.length !== argc || !path) {
    throw Error('Usage: editorial.ts --prompt CITY | --dry-run CAPTURE.json | --validate MANIFEST.json | --draft-payload MANIFEST.json TRACK_MAP.json | --apply-fixture MANIFEST.json LOOPBACK_URL MARKER');
  }
  if (mode === '--prompt') {
    console.log(JSON.stringify(editorialPrompt(findCity(path).code),null,2));
  } else if (['--dry-run','--validate','--draft-payload','--apply-fixture'].includes(mode)) {
    const input: unknown = JSON.parse(await Deno.readTextFile(path));
    const result = mode === '--dry-run' ? editorialManifestReport(prepareEditorialManifest(input)) :
      mode === '--validate' ? editorialManifestReport(validateEditorialManifest(input)) :
      mode === '--draft-payload' ? buildEditorialDraftInput(validateEditorialManifest(input),
        JSON.parse(await Deno.readTextFile(target))) :
      await applyEditorialFixture(input, {url:target,marker,
        apiKey:Deno.env.get('BE03_LOCAL_ANON_KEY') ?? '',serviceKey:Deno.env.get('BE03_LOCAL_SERVICE_KEY') ?? '',
        editorToken:Deno.env.get('BE03_LOCAL_EDITOR_TOKEN') ?? ''});
    console.log(JSON.stringify(result,null,2));
  } else {
    throw Error('Unknown editorial tool mode');
  }
}
