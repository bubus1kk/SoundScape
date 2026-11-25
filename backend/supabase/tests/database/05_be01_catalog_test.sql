BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(10);

CREATE TEMP TABLE be01_tracks AS
SELECT * FROM public.edge_import_audius_batch('[
  {"id":"Be01A","title":"BE01 night city","artist":"Same credit","artistId":"BeArtistA","durationMs":50000,"permalink":"https://audius.co/fixture/a","genre":"BE01 metal","canStream":true},
  {"id":"Be01B","title":"BE01 blue alley","artist":"Same credit","artistId":"BeArtistA","durationMs":51000,"permalink":"https://audius.co/fixture/b","genre":"BE01 synth","mood":"BE01 calm","canStream":true},
  {"id":"Be01C","title":"BE01 third","artist":"Same credit","artistId":"BeArtistB","durationMs":52000,"permalink":"https://audius.co/fixture/c","genre":"BE01 synth","canStream":false}
]'::jsonb);

SELECT is((SELECT count(DISTINCT a.artist_id) FROM public.track_artists a
  JOIN be01_tracks t ON t.id=a.track_id WHERE t.provider_track_id IN ('Be01A','Be01B')),1::bigint,
  'two tracks from one confirmed account use one artist');
SELECT is((SELECT count(DISTINCT a.artist_id) FROM public.track_artists a
  JOIN be01_tracks t ON t.id=a.track_id),2::bigint,'identical display name does not merge different accounts');
SELECT is((SELECT id FROM public.edge_import_audius_batch(
  '[{"id":"Be01B","title":"BE01 blue alley","artist":"Renamed credit","artistId":"BeArtistA","durationMs":51000,"permalink":"https://audius.co/fixture/b","canStream":true}]'
)),(SELECT id FROM be01_tracks WHERE provider_track_id='Be01B'),'repeat import preserves track UUID');
SELECT is((SELECT a.name FROM public.artists a JOIN internal.audius_artist_identity i ON i.artist_id=a.id
  WHERE i.provider_artist_id='BeArtistA'),'Renamed credit','confirmed account retains identity after rename');
SELECT ok(NOT has_function_privilege('authenticated','public.edge_generation_track_ids(text[],text[],text[],integer[],integer[],text,text,text[],uuid[],integer,boolean)','EXECUTE')
  AND NOT has_function_privilege('anon','public.edge_generation_track_ids(text[],text[],text[],integer[],integer[],text,text,text[],uuid[],integer,boolean)','EXECUTE')
  AND NOT has_table_privilege('authenticated','internal.audius_artist_identity','SELECT'),'internal retrieval and identity are private');
SELECT is((SELECT id FROM public.edge_generation_track_ids(
  ARRAY['be01 synth'],ARRAY['be01 calm'],ARRAY['be01 metal'],'{}','{}','ATMOSPHERE','','{}','{}',1
)),(SELECT id FROM be01_tracks WHERE provider_track_id='Be01B'),'facet evidence retrieves better candidate than identical title');

SELECT is((SELECT count(*) FROM public.edge_search_catalog('BE01 blue alley','{}','{}',true,'{}',0,2)),1::bigint,
  'strict catalog accepts freshly confirmed stream');
SELECT ok(jsonb_array_length(public.edge_catalog_search_page(repeat('f',64),0,2,(
  SELECT jsonb_agg(to_jsonb(s)) FROM public.edge_search_catalog('BE01 blue alley','{}','{}',true,'{}',0,2) s
)))=1,'fresh result can be snapshotted');
UPDATE public.track_sources SET verified_at=now()-interval '1 hour' WHERE provider_track_id='Be01B';
SELECT is((SELECT count(*) FROM public.edge_search_catalog('BE01 blue alley','{}','{}',false,'{}',0,2)),0::bigint,
  'stale claim is excluded without reclassification');
SELECT is(public.edge_catalog_search_page(repeat('f',64),0,2),'[]'::jsonb,
  'reading a snapshot never returns an expired stream claim');

SELECT * FROM finish();
ROLLBACK;
