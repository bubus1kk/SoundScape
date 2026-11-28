BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(8);

CREATE TEMP TABLE cleanup_fixture AS
SELECT id, 'users/' || id || '/detached.png' AS detached,
  'users/' || id || '/referenced.png' AS referenced
FROM (SELECT gen_random_uuid() AS id) fixture;
INSERT INTO auth.users(id) SELECT id FROM cleanup_fixture;
UPDATE public.profiles p SET avatar_path = f.referenced FROM cleanup_fixture f WHERE p.id = f.id;
INSERT INTO internal.media_cleanup(path, owner_id)
SELECT detached, id FROM cleanup_fixture UNION ALL SELECT referenced, id FROM cleanup_fixture;

SELECT ok(NOT has_function_privilege('anon','public.edge_claim_pending_media_cleanup()','EXECUTE')
  AND NOT has_function_privilege('authenticated','public.edge_claim_pending_media_cleanup()','EXECUTE')
  AND has_function_privilege('service_role','public.edge_claim_pending_media_cleanup()','EXECUTE'),
  'Only the service role can claim global work');
SELECT is((public.edge_claim_pending_media_cleanup()->'paths'->>0),
  (SELECT detached FROM cleanup_fixture), 'The scheduler claims a detached path without a user session');
SELECT ok((SELECT q.claimed FROM internal.media_cleanup q JOIN cleanup_fixture f ON q.path = f.detached),
  'The path stays reserved across the Storage request');
SELECT ok(NOT EXISTS (SELECT FROM internal.media_cleanup q JOIN cleanup_fixture f ON q.path = f.referenced),
  'Referenced media is discarded from the queue without being claimed');
SELECT is(public.edge_claim_pending_media_cleanup(), NULL::jsonb,
  'A second scheduler run skips recent work');
UPDATE internal.media_cleanup SET maintenance_attempted_at = now() - interval '2 minutes';
SELECT is(public.edge_claim_pending_media_cleanup()->'paths'->>0,
  (SELECT detached FROM cleanup_fixture), 'A failed Storage request can retry the batch');
SELECT public.edge_complete_media_cleanup(id, ARRAY[detached]) FROM cleanup_fixture;
SELECT ok((SELECT q.completed FROM internal.media_cleanup q JOIN cleanup_fixture f ON q.path = f.detached),
  'Completion records the absence of the Storage object');
SELECT is(public.edge_claim_pending_media_cleanup(), NULL::jsonb, 'Completed paths are excluded');
SELECT * FROM finish();
ROLLBACK;
