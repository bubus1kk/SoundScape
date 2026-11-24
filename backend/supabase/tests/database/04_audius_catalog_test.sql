BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(13);

CREATE TEMP TABLE audius_test_batch ON COMMIT DROP AS
SELECT '[
  {"id":"SoundScapeTestUno","title":"Audius pgTAP A","artist":"Audius pgTAP Artist","durationMs":240000,
   "permalink":"https://audius.co/test/uno","genre":"Audius pgTAP Genre","mood":"Audius pgTAP Mood","canStream":true},
  {"id":"SoundScapeTestDos","title":"Audius pgTAP B","artist":"Audius pgTAP Artist","durationMs":220000,
   "permalink":"https://audius.co/test/dos","genre":"Audius pgTAP Genre","mood":"Audius pgTAP Mood","canStream":false}
]'::jsonb AS items;

CREATE TEMP TABLE audius_test_ids ON COMMIT DROP AS
SELECT result.* FROM audius_test_batch b CROSS JOIN LATERAL public.edge_import_audius_batch(b.items) result;

SELECT is((SELECT count(*) FROM audius_test_ids), 2::bigint, 'Both unique tracks are imported');
SELECT results_eq(
  'SELECT r.id, r.provider_track_id FROM audius_test_batch b CROSS JOIN LATERAL edge_import_audius_batch(b.items) r ORDER BY 2',
  'SELECT id, provider_track_id FROM audius_test_ids ORDER BY 2',
  'Repeated imports retain the original SoundScape IDs'
);
SELECT is((SELECT title FROM public.edge_search_catalog('Audius pgTAP Artist', '{}', '{}', false, '{}', 0, 1)),
  'Audius pgTAP A', 'Artist search returns the first page');
SELECT is((SELECT availability FROM public.edge_search_catalog('Audius pgTAP Artist', '{}', '{}', false, '{}', 1, 1)),
  'EXTERNAL_ONLY', 'The second page is distinct and retains external availability');
SELECT is((SELECT count(*) FROM public.edge_search_catalog(
  'Audius pgTAP',
  ARRAY(SELECT id::integer FROM public.genres WHERE name = 'Audius pgTAP Genre'),
  ARRAY(SELECT id::integer FROM public.moods WHERE name = 'Audius pgTAP Mood'),
  true, '{}', 0, 10
)), 1::bigint, 'Genre and mood match the same playable track');

SELECT ok(NOT has_function_privilege('anon', 'public.edge_import_audius_batch(jsonb)', 'EXECUTE'),
  'Guests cannot import tracks');
SELECT ok(NOT has_function_privilege('authenticated', 'public.edge_import_audius_batch(jsonb)', 'EXECUTE')
  AND NOT has_function_privilege('authenticated', 'public.edge_mark_streamable(uuid,text,boolean)', 'EXECUTE'),
  'Signed-in clients cannot import tracks or change stream verification');
SELECT ok(has_function_privilege('service_role', 'public.edge_catalog_search_page(text,integer,integer,jsonb)', 'EXECUTE')
  AND NOT has_function_privilege('anon', 'public.edge_catalog_search_page(text,integer,integer,jsonb)', 'EXECUTE')
  AND NOT has_function_privilege('authenticated', 'public.edge_catalog_search_page(text,integer,integer,jsonb)', 'EXECUTE'),
  'Snapshot operations are reserved for the service role');
SELECT ok(NOT EXISTS (
  SELECT FROM public.track_sources s JOIN audius_test_ids ids ON ids.id = s.track_id
  WHERE s.can_cache OR s.can_download
), 'Neither source permits caching or downloads');

SELECT public.edge_mark_streamable(id, provider_track_id, null)
FROM audius_test_ids WHERE provider_track_id = 'SoundScapeTestUno';
SELECT is((SELECT count(*)
  FROM public.edge_search_catalog('Audius pgTAP Artist', '{}', '{}', false, '{}', 0, 10) result
  JOIN audius_test_ids ids USING (id)
  WHERE ids.provider_track_id = 'SoundScapeTestUno'),
  0::bigint, 'Withdrawn sources are excluded');

SELECT is(public.edge_catalog_search_page(repeat('a', 64), 0, 1),
  NULL::jsonb, 'A missing search snapshot signals a fresh query');
SELECT is(public.edge_catalog_search_page(repeat('a', 64), 0, 1, '["first","second"]'::jsonb),
  '["first"]'::jsonb, 'The first snapshot supplies the first page');
SELECT is(public.edge_catalog_search_page(repeat('a', 64), 1, 1, '["inserted","first","second"]'::jsonb),
  '["second"]'::jsonb, 'A later provider response cannot reorder an active page');

SELECT * FROM finish();
ROLLBACK;
