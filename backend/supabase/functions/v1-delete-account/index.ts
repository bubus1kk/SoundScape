import {
  authenticate, body, HttpError, privilegedHeaders, response, rpc, run, supabaseUrl, uuid,
} from '../_shared/core.ts';
import type { RequestContext } from '../_shared/core.ts';
import { ownedPaths, removeMedia } from '../_shared/media_cleanup.ts';

import { maintenanceAuthorized } from '../_shared/maintenance_auth.ts';

async function processDeletion(userId: string, ctx: RequestContext) {
  const batch = await rpc('edge_begin_account_deletion', { p_user: userId }, ctx);
  const paths = ownedPaths(batch?.paths, userId, 100);
  await removeMedia(paths);
  const ready = await rpc('edge_finish_account_media', { p_user: userId }, ctx);
  if (ready === false) return response({ status: 'PENDING' }, 202);
  if (ready !== true) throw new HttpError(502, 'DATABASE_RESPONSE_INVALID', 'Invalid account deletion response');

  const result = await fetch(`${supabaseUrl()}/auth/v1/admin/users/${userId}`, {
    method: 'DELETE',
    headers: privilegedHeaders(),
    signal: AbortSignal.timeout(8_000),
  }).catch(() => null);
  if (!result || (!result.ok && result.status !== 404)) {
    throw new HttpError(503, 'ACCOUNT_DELETION_UNAVAILABLE', 'Account deletion can be retried');
  }
  await result.body?.cancel();
  return response({ status: 'DELETED' });
}

export default { fetch: run(async (req, ctx) => {
  const request = await body(req);
  if (request.action === 'PROCESS_PENDING') {
    if (Object.keys(request).length !== 1 || !await maintenanceAuthorized(req)) {
      throw new HttpError(401, 'AUTH_REQUIRED', 'Service credential required');
    }
    const userId = await rpc('edge_claim_account_deletion', {}, ctx);
    if (userId === null) return response({ status: 'IDLE' });
    return processDeletion(uuid(userId), ctx);
  }
  const session = await authenticate(req, true, true);
  if (request.confirm !== true || Object.keys(request).length !== 1) {
    throw new HttpError(422, 'VALIDATION_ERROR', 'Explicit account deletion confirmation required');
  }
  return processDeletion(session.userId!, ctx);
}, 'v1-delete-account') };
