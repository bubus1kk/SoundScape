import { audiusList, audiusMetadata, probeAudiusStream } from '../supabase/functions/_shared/audius.ts';
import type { AudiusTrack } from '../supabase/functions/_shared/audius.ts';
import { HttpError, rpc } from '../supabase/functions/_shared/core.ts';

const genres = ['Ambient', 'Classical', 'Jazz', 'Rock', 'Hip-Hop/Rap', 'House', 'Drum & Bass', 'Reggae'];
const maxPerGenre = 16;
const apiIntervalMs = 500;
const batchSize = 20;

type Confirmed = AudiusTrack & { canStream: true };
const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

async function providerCall<T>(request: () => Promise<T>): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    await wait(apiIntervalMs);
    try { return await request(); }
    catch (error) {
      if (!(error instanceof HttpError) || ![429, 503].includes(error.status) || attempt >= 1 ||
          (error.retryAfter ?? 0) > 60) throw error;
      await wait(Math.max(2, error.retryAfter ?? 0) * 1000);
    }
  }
}

async function collect() {
  const sources: { url: string; returned: number; error?: string }[] = [];
  const seen = new Set<string>();
  const confirmed: Confirmed[] = [];
  const rejected: { id?: string; reason: string }[] = [];
  let checked = 0;
  let stopped = false;
  for (const genre of genres) {
    const params = { genre, time: 'month', limit: String(maxPerGenre) };
    const source = {
      url: 'https://api.audius.co/v1/tracks/trending?' + new URLSearchParams(params),
      returned: 0,
      error: undefined as string | undefined,
    };
    sources.push(source);
    let items;
    try { items = await providerCall(() => audiusList('/trending', params)); }
    catch (error) {
      source.error = error instanceof HttpError ? error.code : 'request_failed';
      stopped ||= error instanceof HttpError && error.status === 429;
      if (stopped) break;
      continue;
    }
    source.returned = items.length;
    for (const raw of items) {
      const id = typeof raw?.id === 'string' ? raw.id : undefined;
      if (id && seen.has(id)) {
        rejected.push({ id, reason: 'duplicate' });
        continue;
      }
      if (id) seen.add(id);
      const metadata = audiusMetadata(raw);
      if ('reason' in metadata || metadata.restricted) {
        rejected.push({ id, reason: 'reason' in metadata ? metadata.reason : 'restricted_metadata' });
        continue;
      }
      checked++;
      try {
        const stream = await providerCall(() => probeAudiusStream(metadata.track));
        if ('url' in stream) confirmed.push({ ...metadata.track, canStream: true });
        else rejected.push({ id, reason: stream.reason });
      } catch (error) {
        rejected.push({ id, reason: error instanceof HttpError ? error.code : 'request_failed' });
        if (error instanceof HttpError && error.status === 429) {
          stopped = true;
          break;
        }
      }
    }
    if (stopped) break;
  }
  return { sources, providerRows: sources.reduce((sum, row) => sum + row.returned, 0),
    uniqueProviderIds: seen.size, checked, stopped, confirmed, rejected };
}

if (import.meta.main) {
  const [mode = '--dry-run', target, ...extra] = Deno.args;
  if (extra.length || (mode === '--dry-run' ? target !== undefined : mode !== '--apply-to' || !target)) {
    throw new Error('Usage: import-audius.ts [--dry-run | --apply-to SUPABASE_URL]');
  }
  if (mode === '--apply-to' && target?.replace(/\/$/, '') !== Deno.env.get('SUPABASE_URL')?.replace(/\/$/, ''))
    throw new Error('The explicit import target must match SUPABASE_URL');
  const sample = await collect();
  let persisted = 0;
  if (mode === '--apply-to' && !sample.stopped && sample.sources.every(source => !source.error)) {
    for (let i = 0; i < sample.confirmed.length; i += batchSize) {
      const batch = sample.confirmed.slice(i, i + batchSize);
      const rows = await rpc('edge_import_audius_batch', { p_items: batch });
      if (!Array.isArray(rows) || rows.length !== batch.length) throw new Error('Unexpected import response');
      persisted += rows.length;
    }
  }
  const rejectedByReason: Record<string, number> = {};
  for (const row of sample.rejected) rejectedByReason[row.reason] = (rejectedByReason[row.reason] ?? 0) + 1;
  const { confirmed, ...report } = sample;
  console.log(JSON.stringify({ mode, measuredAt: new Date().toISOString(), ...report,
    verified: confirmed.length, persisted, rejectedByReason, confirmed }, null, 2));
  if (sample.stopped || sample.sources.some(source => source.error)) Deno.exitCode = 2;
}
