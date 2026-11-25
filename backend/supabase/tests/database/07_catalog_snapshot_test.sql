BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(10);

CREATE TEMP TABLE snapshot_fixture AS
SELECT jsonb_agg(jsonb_build_object('id',id,'availability','IN_APP') ORDER BY provider_track_id) AS items
FROM public.edge_import_audius_batch('[
  {"id":"SnapshotA","title":"Snapshot first","artist":"Fixture","durationMs":90000,"permalink":"https://audius.co/fixture/snapshot-a","canStream":true},
  {"id":"SnapshotB","title":"Snapshot second","artist":"Fixture","durationMs":90000,"permalink":"https://audius.co/fixture/snapshot-b","canStream":true}
]');

SELECT ok(
  has_function_privilege('service_role','public.edge_catalog_search_slots(text,integer,integer,integer,integer,jsonb)','EXECUTE')
  AND NOT has_function_privilege('anon','public.edge_catalog_search_slots(text,integer,integer,integer,integer,jsonb)','EXECUTE')
  AND NOT has_function_privilege('authenticated','public.edge_catalog_search_slots(text,integer,integer,integer,integer,jsonb)','EXECUTE'),
  'snapshot RPC accepts service role only');
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,0,2,NULL),NULL::jsonb,
  'missing snapshots are distinguished from empty pages');
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,0,2,items)->'slots',items,
  'snapshot keeps the original order')
FROM snapshot_fixture;

UPDATE public.track_sources SET verified_at=now()-interval '1 hour' WHERE provider_track_id='SnapshotA';
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,0,2,NULL)->'slots',
  jsonb_build_array(NULL,items->1),'aging a source leaves a hole before the second track')
FROM snapshot_fixture;
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,0,1,NULL)->'stale',
  jsonb_build_array(jsonb_build_object('track_id',items->0->>'id','provider_track_id','SnapshotA')),
  'expired source is exposed only as a bounded probe candidate')
FROM snapshot_fixture;
SELECT is(public.edge_catalog_search_slots(repeat('9',64),1,1,1,2,NULL)->'slots',
  jsonb_build_array(items->1),'the second page still starts from the second position')
FROM snapshot_fixture;
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,1,2,NULL)->'stale','[]'::jsonb,
  'reading a prefix for mixed search does not revisit expired earlier positions');

UPDATE public.track_sources SET verified_at=now() WHERE provider_track_id='SnapshotA';
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,0,0,NULL)->'slots',items,
  'successful probe restores the original slot with the same ID')
FROM snapshot_fixture;
UPDATE public.track_sources SET can_stream=false WHERE provider_track_id='SnapshotA';
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,0,2,NULL)->'stale','[]'::jsonb,
  'a permanently restricted source is not queued again');

UPDATE internal.catalog_search_snapshots SET expires_at=now() WHERE query_hash=repeat('9',64);
SELECT is(public.edge_catalog_search_slots(repeat('9',64),0,2,0,2,NULL),NULL::jsonb,
  'expired snapshot can be rebuilt on the next request');

SELECT * FROM finish();
ROLLBACK;
