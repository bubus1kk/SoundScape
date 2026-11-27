import { editorialCities, editorialPrompt } from '../../../scripts/editorial-cities.ts';
import { buildEditorialDraftInput, collectEditorialManifest, editorialManifestReport, prepareEditorialManifest, validateEditorialManifest } from '../../../scripts/editorial.ts';
import { editorialCapture } from '../fixtures/editorial_capture.ts';
import coverHandler from '../../functions/v1-editorial-cover/index.ts';

const now = Date.parse('2026-11-25T12:00:00.000Z');
function check(condition: unknown, message: string) {
  if (!condition) throw Error(message);
}
function rejects(fn: () => unknown) {
  try { fn(); } catch { return; }
  throw Error('Expected validation error');
}

Deno.test('BE-03 concepts cover the 20 distinct approved cities', () => {
  check(editorialCities.length === 20, 'City count');
  check(new Set(editorialCities.map(city => city.code)).size === 20, 'Unique cities');
  check(new Set(editorialCities.map(city => city.directions.join())).size === 20, 'Distinct directions');
  const prompt = editorialPrompt('tokyo');
  check(prompt.model === 'GigaChat-2-Pro' && prompt.response_format.type === 'json_object', 'GigaChat request');
});

Deno.test('BE-03 prepares Tokyo and London through controlled completion and search', () => {
  for (const city of ['tokyo','london'] as const) {
    const m = prepareEditorialManifest(editorialCapture(city, now),now);
    check(m.model === 'GigaChat-2-Pro' && m.city === city, 'Completion');
    check(m.selected.length === 12 && m.candidates.length === 13, 'Selection from search candidates');
    check(m.candidates[0].canStream && !m.candidates[11].canStream, 'Streaming and external evidence');
    check(m.rejected.some(row => row.reason === 'NO_EXACT_SOURCE_EVIDENCE'), 'Missing evidence');
    check(m.rejected.some(row => row.reason === 'DUPLICATE_PROVIDER_ID'), 'Duplicate evidence');
    check(editorialManifestReport(m,now).readyForFixtureImport, 'Fixture can be imported');
    check(!JSON.stringify(m).includes('transient-secret'), 'Raw fields stripped');
    check(validateEditorialManifest(JSON.parse(JSON.stringify(m)),now).selected.length === 12, 'Round trip');
  }
});

Deno.test('BE-03 collects both pilot cities using bounded controlled providers', async () => {
  for (const city of ['tokyo','london'] as const) {
    const capture = editorialCapture(city,now);
    const calls: string[] = [];
    const manifest = await collectEditorialManifest(city,'SYNTHETIC_QA',{
      completion: (request) => {
        calls.push(request.model);
        return Promise.resolve(capture.completion);
      },
      search: (query,limit) => {
        check(limit===15,'Bounded result count');
        calls.push('search');
        return Promise.resolve(capture.searches.find(item=>item.query===query)?.rows ?? []);
      },
      verify: (track) => {
        calls.push(track.id);
        return Promise.resolve(capture.evidence.find(item=>item.id===track.id) ?? null);
      },
    }, { now:()=>now,draft:capture.draft,selected:capture.selected });
    check(calls[0]==='GigaChat-2-Pro','Completion comes first');
    check(calls.filter(call=>call==='search').length===3,'Three concept queries');
    check(calls.filter(call=>call==='BE03Fixture-'+city+'-1').length===1,'Verify a provider ID once');
    check(manifest.selected.length===12 && manifest.rejected.length===2,'Controlled selections and rejections');
  }
});

Deno.test('BE-03 AI cannot supply track IDs and manifest cannot reference unknown tracks', () => {
  const capture = editorialCapture('tokyo',now);
  const content = JSON.parse(capture.completion.choices[0].message.content);
  content.trackIds = ['made-up'];
  capture.completion.choices[0].message.content = JSON.stringify(content);
  rejects(() => prepareEditorialManifest(capture,now));
  const manifest = prepareEditorialManifest(editorialCapture('tokyo',now),now);
  rejects(() => validateEditorialManifest({...manifest,selected:['made-up']},now));
  rejects(() => validateEditorialManifest({...manifest,provenance:'HISTORICAL'},now));
});

Deno.test('BE-03 conflicting provider identity is rejected and stale evidence is not renewed', () => {
  const capture = editorialCapture('london',now);
  capture.searches[1].rows[0] = {...capture.searches[1].rows[0],title:'[BE-03 QA] Contradictory title'};
  capture.selected = capture.selected.slice(1);
  const manifest = prepareEditorialManifest(capture,now);
  check(!manifest.candidates.some(t => t.id === 'BE03Fixture-london-1'), 'Conflicted identity removed');
  check(manifest.rejected.some(row => row.reason === 'CONFLICTING_PROVIDER_IDENTITY'), 'Conflict reported');
  const report = editorialManifestReport(manifest,now + 3600_000);
  check(!report.readyForFixtureImport && report.staleInApp.length > 0, 'Evidence is stale');
});

Deno.test('BE-03 builds an ordered editor payload only from canonical catalog UUIDs', () => {
  const manifest = prepareEditorialManifest(editorialCapture('tokyo',now),now);
  const ids = manifest.selected.map(() => crypto.randomUUID());
  const trackIdsByProvider = Object.fromEntries(manifest.selected.map((id,index) => [id,ids[index]]));
  const mapping = {placeId:crypto.randomUUID(),trackIdsByProvider};
  const draft = buildEditorialDraftInput(manifest,mapping);
  check(draft.trackIds.join()===ids.join(),'Selected order translated to SoundScape UUIDs');
  check(draft.title.startsWith('[BE-03 QA]'),'Synthetic draft marked');
  trackIdsByProvider[manifest.selected[1]] = ids[0];
  rejects(() => buildEditorialDraftInput(manifest,mapping));
});

Deno.test('BE-03 managed cover checks authorization, size and signature before uploading', async () => {
  const originalFetch = globalThis.fetch;
  const originalEnv = Deno.env.get;
  const keys: Record<string,string> = {SUPABASE_URL:'https://supabase.invalid',
    SUPABASE_ANON_KEY:'fixture-publishable', SUPABASE_SERVICE_ROLE_KEY:'fixture-service'};
  const session = crypto.randomUUID();
  const userId = crypto.randomUUID();
  const token = 'e30.'+btoa(JSON.stringify({session_id:session}))+'.fixture';
  const validPng = new Uint8Array([137,80,78,71,13,10,26,10]);
  const path = 'https://supabase.invalid/functions/v1/v1-editorial-cover';
  let allowed = false;
  const uploads: string[] = [];
  try {
    Deno.env.get = key => keys[key];
    globalThis.fetch = (input, init) => {
      const url = new URL(String(input));
      check(url.hostname==='supabase.invalid','Only the controlled Supabase may be called');
      if (url.pathname==='/auth/v1/user') return Promise.resolve(Response.json({id:userId}));
      if (url.pathname.endsWith('/edge_verify_session')) return Promise.resolve(Response.json(true));
      if (url.pathname.endsWith('/edge_can_upload_editorial_cover')) return Promise.resolve(Response.json(allowed));
      if (url.pathname.endsWith('/edge_reserve_quota')) return Promise.resolve(Response.json({allowed:true}));
      check(url.pathname.startsWith('/storage/v1/object/soundscape-media/editorial/covers/'),'Managed storage path');
      check(init?.method==='POST' && new Headers(init.headers).get('apikey')==='fixture-service','Storage authority');
      uploads.push(url.pathname);
      return Promise.resolve(Response.json({}));
    };
    const request = (body: Uint8Array<ArrayBuffer>, extra: Record<string,string> = {}) =>
      coverHandler.fetch(new Request(path,{method:'POST',body,headers:{
        apikey:keys.SUPABASE_ANON_KEY,authorization:'Bearer '+token,'content-type':'image/png',...extra}}));
    check((await request(validPng)).status===403,'Ordinary user rejected');
    allowed = true;
    check((await request(new Uint8Array([1,2,3]))).status===422,'Mismatched signature rejected');
    check((await request(validPng,{'content-length':'10485761'})).status===413,'Size limit enforced');
    check(uploads.length===0,'Rejected requests perform no storage writes');
    const response = await request(validPng);
    const {coverPath} = await response.json();
    check(response.status===201 && [
      '/storage/v1/object/soundscape-media/'+coverPath,
    ].join()===uploads.join(),'Created immutable path is returned');
  } finally {
    Deno.env.get = originalEnv;
    globalThis.fetch = originalFetch;
  }
});
