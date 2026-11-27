import { HttpError, privilegedHeaders, supabaseUrl } from './core.ts';

export function ownedPaths(value: unknown, userId: string, limit: number): string[] {
  if (!Array.isArray(value) || value.length > limit || value.some(path =>
    typeof path !== 'string' || !path.startsWith(`users/${userId}/`) ||
    path.length === `users/${userId}/`.length || path.includes('\0')
  )) {
    throw new HttpError(502, 'DATABASE_RESPONSE_INVALID', 'Invalid media cleanup response');
  }
  return value;
}

export async function removeMedia(paths: string[]): Promise<void> {
  if (!paths.length) return;
  const result = await fetch(`${supabaseUrl()}/storage/v1/object/soundscape-media`, {
    method: 'DELETE',
    headers: { ...privilegedHeaders(), 'content-type': 'application/json' },
    body: JSON.stringify({ prefixes: paths }),
    signal: AbortSignal.timeout(8_000),
  }).catch(() => null);
  if (!result?.ok) throw new HttpError(503, 'STORAGE_UNAVAILABLE', 'Media cleanup can be retried');
  await result.body?.cancel();
}
