/** SoundScape Edge request correlation and allowlisted JSON diagnostics.
 * Never include URLs, query strings, user identifiers, exception messages,
 * request/response bodies, Authorization, provider tokens or share tokens.
 */
export type RequestContext = Readonly<{
  requestId: string;
  operation: string;
  startedAtMs: number;
}>;

const safeName = (name: string, fallback: string): string =>
  /^[a-z][a-z0-9-]{0,39}$/.test(name) ? name : fallback;
const safeCode = (value: string): string =>
  /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'UNCLASSIFIED';

export function newRequestContext(operation: string): RequestContext {
  return { requestId: crypto.randomUUID(), operation: safeName(operation, 'edge'), startedAtMs: Date.now() };
}

/** Sanitizes all dynamic log attributes. Only supported fields are serialized. */
export function logRequest(ctx: RequestContext, status: number, code?: string): void {
  const result = {
    event: status >= 400 ? 'edge_request_failed' : 'edge_request_complete',
    requestId: ctx.requestId,
    operation: ctx.operation,
    status: Number.isInteger(status) && status >= 100 && status <= 599 ? status : 500,
    durationMs: Math.max(0, Date.now() - ctx.startedAtMs),
    ...(status >= 400 ? { code: safeCode(code || 'INTERNAL_ERROR') } : {}),
  };
  const line = JSON.stringify(result);
  if (status >= 400) console.error(line);
  else console.log(line);
}

export function logProviderFailure(
  ctx: RequestContext | undefined, provider: 'AUDIUS' | 'GIGACHAT',
  stage: 'search' | 'stream' | 'oauth' | 'generation', category: string, upstreamStatus?: number,
): void {
  const result = {
    event: 'external_api_failure',
    ...(ctx ? { requestId: ctx.requestId, operation: ctx.operation } : {}),
    provider, stage, category: safeCode(category),
    ...(upstreamStatus !== undefined && Number.isInteger(upstreamStatus) && upstreamStatus >= 100 && upstreamStatus <= 599
      ? { upstreamStatus } : {}),
  };
  console.error(JSON.stringify(result));
}

export function logInternalFailure(
  ctx: RequestContext | undefined, kind: 'RPC_ERROR' | 'RPC_INVALID_RESPONSE' |
    'DATABASE_QUERY_ERROR' | 'LEASE_RELEASE_ERROR' | 'QUOTA_INVALID_RESPONSE',
  component: string, status?: number,
): void {
  const result = {
    event: 'internal_dependency_failure',
    ...(ctx ? { requestId: ctx.requestId, operation: ctx.operation } : {}),
    category: kind,
    // No SQL query string, RPC arguments, URLs, error messages or user data.
    component: /^[a-z_]{1,50}$/.test(component) ? component : 'unknown',
    ...(status !== undefined && Number.isInteger(status) && status >= 100 && status <= 599
      ? { upstreamStatus: status } : {}),
  };
  console.error(JSON.stringify(result));
}

/** A rejected quota is normal application behavior, not a database exception. */
export function logQuotaLimited(ctx: RequestContext | undefined,
  reason: string, retryAfter: number): void {
  const known = ['GLOBAL_LIMIT','USER_LIMIT','CONCURRENT_LIMIT'];
  console.warn(JSON.stringify({
    event: 'quota_rejected',
    ...(ctx ? {requestId:ctx.requestId,operation:ctx.operation} : {}),
    reason: known.includes(reason) ? reason : 'UNCLASSIFIED',
    retryAfter: Number.isInteger(retryAfter) && retryAfter >= 1 && retryAfter <= 86400 ? retryAfter : null,
  }));
}
