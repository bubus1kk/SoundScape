import { secretKey } from './core.ts';

export async function maintenanceAuthorized(req: Request): Promise<boolean> {
  const key = req.headers.get('apikey');
  if (!key) return false;
  const encoder = new TextEncoder();
  const secret = secretKey();
  const verifier = await crypto.subtle.importKey(
    'raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify'],
  );
  const legacy = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  const proof = await crypto.subtle.sign('HMAC', verifier, encoder.encode(key));
  return await crypto.subtle.verify('HMAC', verifier, proof, encoder.encode(secret)) ||
    (!!legacy && await crypto.subtle.verify('HMAC', verifier, proof, encoder.encode(legacy)));
}
