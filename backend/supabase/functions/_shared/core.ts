// SoundScape Edge: common validation, explicit authentication, safe errors.
export type Json = Record<string, unknown>;
export type Session = { userId: string | null; jwt: string | null };
export class HttpError extends Error {
  constructor(public status: number, public code: string, message: string,
              public retryAfter?: number) { super(message); }
}
export const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function text(v: unknown, max: number, name: string, min = 1): string {
  if (typeof v !== 'string' || v.trim().length < min || v.length > max)
    throw new HttpError(400, 'VALIDATION_ERROR', `Invalid ${name}`);
  return v.trim();
}
export function int(v: unknown, fallback: number, min: number, max: number, name: string): number {
  const x = v === undefined ? fallback : v;
  if (!Number.isInteger(x) || (x as number) < min || (x as number) > max)
    throw new HttpError(400, 'VALIDATION_ERROR', `Invalid ${name}`);
  return x as number;
}
export function bool(v: unknown, fallback = false): boolean {
  if (v === undefined) return fallback;
  if (typeof v !== 'boolean') throw new HttpError(400, 'VALIDATION_ERROR', 'Expected boolean');
  return v;
}
export function uuid(v: unknown, name = 'id'): string {
  if (typeof v !== 'string' || !uuidRe.test(v))
    throw new HttpError(400, 'VALIDATION_ERROR', `Invalid ${name}`);
  return v;
}
export function ids(v: unknown): number[] {
  if (v === undefined) return [];
  if (!Array.isArray(v) || v.length > 20 || v.some(x => !Number.isInteger(x) || x < 1 || x > 32767)
      || new Set(v).size !== v.length) throw new HttpError(400, 'VALIDATION_ERROR', 'Invalid ID list');
  return v;
}
export async function body(req: Request): Promise<Json> {
  if (req.method !== 'POST') throw new HttpError(405, 'METHOD_NOT_ALLOWED', 'POST required');
  if (!(req.headers.get('content-type')||'').toLowerCase().includes('application/json'))
    throw new HttpError(400, 'VALIDATION_ERROR', 'JSON required');
  const len = Number(req.headers.get('content-length') || 0);
  if (len > 12000) throw new HttpError(413, 'PAYLOAD_TOO_LARGE', 'Request too large');
  let raw = ''; try { raw = await req.text(); } catch { throw new HttpError(400,'VALIDATION_ERROR','Invalid request'); }
  if (raw.length > 12000) throw new HttpError(413, 'PAYLOAD_TOO_LARGE', 'Request too large');
  try { const result = JSON.parse(raw); if (result && typeof result === 'object' && !Array.isArray(result)) return result; }
  catch { /* handled */ }
  throw new HttpError(400,'VALIDATION_ERROR','Expected JSON object');
}
export function response(data: unknown, status = 200, extra?: Record<string,string>): Response {
  return new Response(JSON.stringify(data), {status,headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff',...extra}});
}
export function errorResponse(err: unknown): Response {
  const e = err instanceof HttpError ? err : new HttpError(500,'INTERNAL_ERROR','Internal server error');
  return response({error:{code:e.code,message:e.message,requestId:crypto.randomUUID()}},e.status,
      e.retryAfter ? {'retry-after':String(e.retryAfter)} : undefined);
}
export function run(fn:(req:Request)=>Promise<Response>): (req:Request)=>Promise<Response> {
  return async (req:Request)=>{
    try { if(req.method==='OPTIONS') return new Response(null,{status:204,headers:{allow:'POST, OPTIONS'}});
          return await fn(req); }
    catch(e){if(!(e instanceof HttpError)) console.error('soundscape edge unexpected failure',e instanceof Error?e.name:'error'); return errorResponse(e);}
  };
}
function keyFromEnv(plural:string,singular:string):string {
  const p=Deno.env.get(plural);
  if (p) { try { const j=JSON.parse(p); if(typeof j.default==='string') return j.default; } catch {} }
  return Deno.env.get(singular)||'';
}
export function supabaseUrl():string {
  const x=Deno.env.get('SUPABASE_URL');
  if(!x || !/^https?:\/\//.test(x)) throw new HttpError(503,'NOT_CONFIGURED','Supabase URL unavailable');
  return x.replace(/\/$/,'');
}
export function publishableKey(): string {
  const x=keyFromEnv('SUPABASE_PUBLISHABLE_KEYS','SUPABASE_PUBLISHABLE_KEY') || Deno.env.get('SUPABASE_ANON_KEY') || '';
  if(!x) throw new HttpError(503,'NOT_CONFIGURED','Publishable key unavailable');return x;
}
export function secretKey():string {
  const x=keyFromEnv('SUPABASE_SECRET_KEYS','SUPABASE_SECRET_KEY') || Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') || '';
  if(!x) throw new HttpError(503,'NOT_CONFIGURED','Secret key unavailable');return x;
}
// No blanket JWT verification disabled without an explicit auth check: publishable is not a user JWT.
export async function authenticate(req: Request, required = false): Promise<Session> {
  const key = req.headers.get('apikey');
  const accepted=[publishableKey(),Deno.env.get('SUPABASE_ANON_KEY')||''].filter(Boolean);
  if (!key || !accepted.includes(key)) throw new HttpError(401,'INVALID_API_KEY','Valid publishable key required');
  const authorization=req.headers.get('authorization');
  if (!authorization) {
    if (required) throw new HttpError(401,'AUTH_REQUIRED','Sign in required');
    return {userId:null,jwt:null};
  }
  const match=/^Bearer\s+([^\s]+)$/i.exec(authorization);
  if (!match || match[1].startsWith('sb_') || accepted.includes(match[1]))
     throw new HttpError(401,'INVALID_JWT','Invalid access token');
  const res=await fetch(supabaseUrl()+'/auth/v1/user',{
    headers:{apikey:publishableKey(),Authorization:`Bearer ${match[1]}`},signal:AbortSignal.timeout(8000)
  }).catch(()=>{throw new HttpError(503,'AUTH_UNAVAILABLE','Authentication unavailable');});
  if(!res.ok) throw new HttpError(res.status===401||res.status===403?401:503,'INVALID_JWT','Invalid or expired access token');
  const user=await res.json();
  if(!uuidRe.test(user.id||'')) throw new HttpError(401,'INVALID_JWT','Invalid user');
  return {userId:user.id,jwt:match[1]};
}
export function publicHeaders(jwt: string | null):Record<string,string> {
  return {apikey:publishableKey(),...(jwt?{Authorization:`Bearer ${jwt}`}:{})};
}
// Dedicated server-side only Data API. Secret key NEVER comes from a client request.
export async function adminRest(path: string, init: RequestInit = {}):Promise<Response> {
  const res=await fetch(supabaseUrl()+'/rest/v1/'+path,{
    ...init,
    headers:{'apikey':secretKey(),'content-type':'application/json',...(init.headers as Record<string,string>||{})},
    signal:AbortSignal.timeout(9000),
  }).catch(()=>{throw new HttpError(503,'DATABASE_UNAVAILABLE','Database unavailable');});
  return res;
}
/** Parse the body returned by a successful PostgREST RPC call. */
export async function parseRpcSuccess(res: Response): Promise<unknown> {
  if (res.status === 204 || res.status === 205) return null;
  const raw = await res.text();
  if (!raw.trim()) return null;
  try {
    return JSON.parse(raw);
  } catch {
    throw new HttpError(502, 'DATABASE_RESPONSE_INVALID',
      'Invalid internal database response');
  }
}

export async function rpc(name:string,payload:Record<string,unknown>):Promise<any> {
  if(!/^edge_[a-z_]+$/.test(name)) throw new HttpError(500,'INTERNAL_ERROR','Invalid RPC');
  const res=await adminRest('rpc/'+name,{method:'POST',body:JSON.stringify(payload)});
  if(!res.ok){
    const problem=await res.json().catch(()=>({}));
    // Do not leak DB internals. Map expected PostgreSQL status to public API.
    if(problem?.code==='42501'||res.status===403)throw new HttpError(403,'FORBIDDEN','Operation forbidden');
    if(problem?.code==='22023')throw new HttpError(422,'VALIDATION_ERROR','Operation validation failed');
    console.error('internal rpc status',name,res.status);
    throw new HttpError(503,'DATABASE_UNAVAILABLE','Internal database operation failed');
  }
  try {
    return await parseRpcSuccess(res);
  } catch (e) {
    if (e instanceof HttpError && e.code === 'DATABASE_RESPONSE_INVALID') {
      console.error('internal rpc unexpected response', name, res.status);
    }
    throw e;
  }
}
export async function reserve(operation:string,userId:string|null):Promise<string|null> {
  const result=await rpc('edge_reserve_quota',{p_operation:operation,p_user:userId});
  if(result?.allowed!==true) throw new HttpError(429,'RATE_LIMITED','Request limit exceeded',Number(result?.retryAfter)||60);
  return typeof result.leaseId==='string'?result.leaseId:null;
}
export async function release(lease:string|null){if(lease){try {await rpc('edge_release_generation',{p_lease:lease});}catch{console.error('Failed to release generation lease');}}}
export async function select(table:string,query:string,jwt:string|null,privileged=false):Promise<any[]> {
  if(!/^[a-z_]+$/.test(table)) throw new HttpError(500,'INTERNAL_ERROR','Invalid table');
  const url=supabaseUrl()+'/rest/v1/'+table+'?'+query;
  const res=await fetch(url,{headers:privileged?{'apikey':secretKey()}:publicHeaders(jwt),signal:AbortSignal.timeout(9000)})
    .catch(()=>{throw new HttpError(503,'DATABASE_UNAVAILABLE','Database unavailable');});
  if(!res.ok) throw new HttpError(503,'DATABASE_UNAVAILABLE','Database query failed');
  const data=await res.json();return Array.isArray(data)?data:[];
}
export async function sha256(s:string):Promise<string> {
  const bytes=new TextEncoder().encode(s);const b=await crypto.subtle.digest('SHA-256',bytes);
  return Array.from(new Uint8Array(b),x=>x.toString(16).padStart(2,'0')).join('');
}
export const isSafeExternalUrl=(v:unknown):v is string=>typeof v==='string' && /^https:\/\/(www\.)?audius\.co\/[\w\-/%?=.&]+$/.test(v);
