/** Safe, bounded upstream diagnostics. Never log URLs, payloads, headers or exception text. */
import { HttpError } from './core.ts';
import { logProviderFailure } from './observability.ts';
import type { RequestContext } from './observability.ts';

export type Provider = 'AUDIUS' | 'GIGACHAT';
export type ProviderStage = 'search' | 'stream' | 'oauth' | 'generation';
export type NetworkCategory = 'TIMEOUT' | 'DNS' | 'TLS' | 'NETWORK';

/** Exception messages are read *only* to classify a failure, never written to logs. */
export function networkCategory(error: unknown): NetworkCategory {
  const pieces: string[] = [];
  let current: unknown = error;
  for (let i = 0; i < 3 && current && typeof current === 'object'; i++) {
    const item = current as { name?: unknown; code?: unknown; message?: unknown; cause?: unknown };
    pieces.push(String(item.name ?? ''), String(item.code ?? ''), String(item.message ?? ''));
    current = item.cause;
  }
  const value = pieces.join(' ').toLowerCase();
  if (/timeout|timed.out|etimedout|deadline|aborterror/.test(value)) return 'TIMEOUT';
  if (/certificate|cert_|unknownissuer|tls|ssl|handshake|invalid peer/.test(value)) return 'TLS';
  if (/dns|lookup|resolve|enotfound|eai_again|no such host/.test(value)) return 'DNS';
  return 'NETWORK';
}

export function providerLog(provider: Provider, stage: ProviderStage, category: string,
  status?: number, ctx?: RequestContext): void {
  logProviderFailure(ctx,provider,stage,category,status);
}

export function networkFailure(provider: Provider, stage: ProviderStage, error: unknown, ctx?: RequestContext): HttpError {
  const category = networkCategory(error);
  providerLog(provider, stage, category, undefined, ctx);
  const service = provider === 'GIGACHAT' ? 'AI' : 'PROVIDER';
  return new HttpError(503, service + '_UNAVAILABLE',
    provider === 'GIGACHAT' ? 'GigaChat temporarily unavailable' : 'Audius temporarily unavailable');
}

function retrySeconds(header: string | null): number {
  if (header) {
    const n = Number(header);
    if (Number.isInteger(n) && n >= 1 && n <= 3600) return n;
    const date = Date.parse(header);
    if (Number.isFinite(date)) return Math.max(1, Math.min(3600, Math.ceil((date - Date.now()) / 1000)));
  }
  return 60;
}

/** Map *provider* status to an app-side status; never use provider 401 as user JWT 401. */
export function providerHttpFailure(
  provider: Provider, stage: ProviderStage, status: number, retryAfter: string | null = null, ctx?: RequestContext,
): HttpError {
  const category = status === 429 ? 'UPSTREAM_RATE_LIMIT' :
    status >= 500 ? 'UPSTREAM_SERVER_ERROR' :
    status === 401 || status === 403 ? 'UPSTREAM_AUTH_REJECTED' :
    status === 400 ? 'UPSTREAM_REQUEST_REJECTED' : 'UPSTREAM_HTTP_ERROR';
  providerLog(provider, stage, category, status, ctx);
  if (status === 429) return new HttpError(429, 'RATE_LIMITED',
    provider === 'GIGACHAT' ? 'AI provider rate limit exceeded' : 'Audius rate limit exceeded', retrySeconds(retryAfter));
  if (status >= 500) return new HttpError(503, provider === 'GIGACHAT' ? 'AI_UNAVAILABLE' : 'PROVIDER_UNAVAILABLE',
    provider === 'GIGACHAT' ? 'GigaChat temporarily unavailable' : 'Audius temporarily unavailable');
  if (provider === 'AUDIUS') return new HttpError(502, 'PROVIDER_ERROR', 'Audius rejected the request');
  if (stage === 'oauth') return new HttpError(502, 'AI_OAUTH_REJECTED',
    'GigaChat authorization rejected; check server credentials and scope');
  return new HttpError(502, 'AI_UPSTREAM_ERROR',
    'GigaChat rejected generation request; check model and provider permissions');
}

export function invalidProviderResponse(provider: Provider, stage: ProviderStage, ctx?: RequestContext): HttpError {
  providerLog(provider, stage, 'INVALID_RESPONSE', undefined, ctx);
  return new HttpError(502, provider === 'GIGACHAT' ? 'AI_INVALID_RESULT' : 'PROVIDER_INVALID_RESPONSE',
    provider === 'GIGACHAT' ? 'GigaChat returned an invalid response' : 'Audius returned an invalid response');
}
