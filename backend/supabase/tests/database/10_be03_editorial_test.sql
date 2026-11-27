BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT no_plan();

CREATE TEMP TABLE editorial_fixture ON COMMIT DROP AS
SELECT gen_random_uuid() AS editor, gen_random_uuid() AS owner, gen_random_uuid() AS ordinary,
  gen_random_uuid() AS admin, gen_random_uuid() AS tokyo, gen_random_uuid() AS london, gen_random_uuid() AS other,
  gen_random_uuid() AS unknown, gen_random_uuid() AS legacy,
  ARRAY(SELECT gen_random_uuid() FROM generate_series(1,16)) AS tracks;
GRANT SELECT ON editorial_fixture TO anon, authenticated;
INSERT INTO auth.users(id,raw_app_meta_data)
SELECT editor,'{}'::jsonb FROM editorial_fixture UNION ALL
SELECT owner,'{"soundscape_editorial_owner":true}'::jsonb FROM editorial_fixture UNION ALL
SELECT ordinary,'{"soundscape_editorial_owner":true}'::jsonb FROM editorial_fixture UNION ALL
SELECT admin,'{}'::jsonb FROM editorial_fixture;
UPDATE public.profiles SET role_code='EDITOR' WHERE id=(SELECT editor FROM editorial_fixture);
UPDATE public.profiles SET role_code='ADMIN' WHERE id IN (SELECT owner FROM editorial_fixture UNION ALL SELECT admin FROM editorial_fixture);
INSERT INTO public.places(id,title,country_code,geom,is_published)
SELECT tokyo,'Токио','JP',extensions.ST_GeogFromText('POINT(139.69 35.69)'),true FROM editorial_fixture UNION ALL
SELECT london,'London','GB',extensions.ST_GeogFromText('POINT(-0.13 51.51)'),true FROM editorial_fixture UNION ALL
SELECT other,'Yokohama','JP',extensions.ST_GeogFromText('POINT(139.64 35.44)'),true FROM editorial_fixture;
INSERT INTO public.playlists(id,owner_id,place_id,title,description,visibility,is_editorial,published_at)
SELECT legacy,owner,tokyo,'BE-03 legacy snapshot','Fixture','PUBLIC',true,now() FROM editorial_fixture;
INSERT INTO public.tracks(id,title,duration_ms)
SELECT id,'BE-03 synthetic SQL track '||n,1000 FROM editorial_fixture, unnest(tracks) WITH ORDINALITY a(id,n);
INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,can_stream,verified_at)
SELECT id,'AUDIUS',id::text,
  CASE n WHEN 11 THEN 'https://audius.co/test-artist/other-track' WHEN 12 THEN 'https://audius.co/test-artist/test-track' WHEN 13 THEN 'https://audius.co/search/test-track' END,
  n NOT IN (11,12,13),now() FROM editorial_fixture, unnest(tracks) WITH ORDINALITY a(id,n);

INSERT INTO public.genres(id,name) VALUES (32001,'BE-03 SQL genre');
INSERT INTO public.tags(id,name) VALUES (32001,'BE-03 SQL tag');
INSERT INTO storage.objects(bucket_id,name,metadata)
VALUES ('soundscape-media','editorial/covers/be03-pgtap.png','{"size":100,"mimetype":"image/png"}');

SET LOCAL ROLE anon;
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',0)) FROM editorial_fixture$q$,
  'PT401','AUTH_REQUIRED','Guest cannot create');
SELECT throws_ok($q$SELECT public.get_editorial_draft(jsonb_build_object('placeId',tokyo)) FROM editorial_fixture$q$,
  'PT401','AUTH_REQUIRED','Guest cannot read drafts');
RESET ROLE;

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',(SELECT ordinary::text FROM editorial_fixture),true);
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',0)) FROM editorial_fixture$q$,
  'PT403','EDITORIAL_EDITOR_REQUIRED','Ordinary user cannot edit even with owner metadata');
SELECT set_config('request.jwt.claim.sub',(SELECT editor::text FROM editorial_fixture),true);
SELECT is(jsonb_array_length(public.list_editorial_cities('{}')),20,'Fixed list has 20 entries');
SELECT throws_ok($q$SELECT public.save_editorial_draft('{"placeId":"invalid","expectedRevision":0}')$q$,
  'PT422','EDITORIAL_INVALID_PLACE_ID','Invalid place UUID is diagnosed');
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision','invalid')) FROM editorial_fixture$q$,
  'PT422','EDITORIAL_INVALID_REVISION','Invalid draft revision is diagnosed');
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',other,'expectedRevision',0)) FROM editorial_fixture$q$,
  'PT404','EDITORIAL_PLACE_NOT_FOUND_OR_AMBIGUOUS','Ineligible city UUID is rejected');
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',0,'trackIds',ARRAY[unknown])) FROM editorial_fixture$q$,
  'PT422','EDITORIAL_UNKNOWN_TRACK','Unknown track UUID is rejected');
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',0,'trackIds',ARRAY['bad'])) FROM editorial_fixture$q$,
  'PT422','EDITORIAL_INVALID_TRACK_ID','Malformed track UUID is rejected');
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',0,'coverPath','editorial/missing.png')) FROM editorial_fixture$q$,
  'PT422','EDITORIAL_INVALID_COVER','Missing artwork is rejected at attachment');
SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',0,'title','',
  'trackIds',tracks[1:11]))->>'playlistId' FROM editorial_fixture),(SELECT legacy::text FROM editorial_fixture),
  'Creating from a legacy publication preserves its UUID');
SELECT is((SELECT public.get_playlist(jsonb_build_object('playlistId',legacy))->>'title' FROM editorial_fixture),
  'BE-03 legacy snapshot','Legacy publication remains intact while drafting');
SELECT ok((SELECT public.check_editorial_draft(jsonb_build_object('placeId',tokyo))->'issues'
  @> '[{"code":"TRACK_COUNT_12_TO_15"},{"code":"TITLE_REQUIRED"},{"code":"COVER_REQUIRED_OR_INVALID"}]'
  FROM editorial_fixture),'Reports count 11 and missing metadata');
SELECT throws_ok($q$SELECT public.approve_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',1)) FROM editorial_fixture$q$,
  'PT403','EDITORIAL_OWNER_REQUIRED','Editor cannot approve');
SELECT throws_ok($q$SELECT public.publish_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',1)) FROM editorial_fixture$q$,
  'PT403','EDITORIAL_OWNER_REQUIRED','Editor cannot publish');
SELECT throws_ok('SELECT * FROM internal.editorial_drafts','42501',NULL,'Direct draft read denied');
SELECT throws_ok('UPDATE internal.editorial_drafts SET title=''BYPASS''','42501',NULL,'Direct draft update denied');
SELECT throws_ok('UPDATE public.playlists SET title=''BYPASS''','42501',NULL,'Direct playlist update denied');
SELECT throws_ok($q$INSERT INTO storage.objects(bucket_id,name) VALUES ('soundscape-media','editorial/covers/bypass-pgtap.png')$q$,
  '42501',NULL,'Managed covers cannot bypass the upload handler');
SELECT ok(NOT has_function_privilege('authenticated','public.editorial_import_fixture_batch(uuid,jsonb)','EXECUTE'),
  'An editor credential cannot execute the fixture importer');
SELECT set_config('request.jwt.claim.sub',(SELECT admin::text FROM editorial_fixture),true);
SELECT throws_ok($q$SELECT public.approve_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',1)) FROM editorial_fixture$q$,
  'PT403','EDITORIAL_OWNER_REQUIRED','Technical ADMIN cannot approve without designation');
SELECT set_config('request.jwt.claim.sub',(SELECT owner::text FROM editorial_fixture),true);
SELECT throws_ok($q$SELECT public.approve_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',1)) FROM editorial_fixture$q$,
  'PT422','EDITORIAL_NOT_READY','Incomplete revision cannot be approved');
SELECT throws_ok($q$SELECT public.publish_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',1)) FROM editorial_fixture$q$,
  'PT409','EDITORIAL_APPROVAL_REQUIRED','Publication needs explicit approval');

SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',1,'title','Токио',
  'description','Тестовое описание','genreIds',ARRAY[32001],'tagIds',ARRAY[32001],
  'coverPath','editorial/covers/be03-pgtap.png','trackIds',tracks))->>'revision' FROM editorial_fixture),'2','Stores 16-track work in progress');
SELECT ok((SELECT public.check_editorial_draft(jsonb_build_object('placeId',tokyo))->'issues'
  @> '[{"code":"TRACK_COUNT_12_TO_15"},{"code":"NO_VERIFIED_SOURCE"}]' FROM editorial_fixture),
  'Rejects 16 tracks and external search URLs');
SELECT throws_ok($q$SELECT public.approve_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',2)) FROM editorial_fixture$q$,
  'PT422','EDITORIAL_NOT_READY','16-track revision cannot be approved');
SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',2,
  'trackIds',tracks[1:11]||tracks[1]))->>'revision' FROM editorial_fixture),'3','Duplicate can be fixed before approval');
SELECT ok((SELECT public.check_editorial_draft(jsonb_build_object('placeId',tokyo))->'issues'
  @> '[{"code":"DUPLICATE_TRACK"}]' FROM editorial_fixture),'Duplicate track is diagnosed');
SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',3,
  'trackIds',tracks[1:12]))->>'revision' FROM editorial_fixture),'4','Valid 12 tracks including exact external source');
SELECT throws_ok($q$SELECT public.approve_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',3)) FROM editorial_fixture$q$,
  'PT409','EDITORIAL_REVISION_CONFLICT','Approval on stale revision fails');
SELECT is((SELECT public.approve_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',4))->>'approvedRevision'
  FROM editorial_fixture),'4','Owner approval is revision specific');
SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',4,
  'trackIds',ARRAY[tracks[2],tracks[1]]||tracks[3:12]))->>'approvedRevision' FROM editorial_fixture),NULL,
  'Reordering invalidates approval');
SELECT throws_ok($q$SELECT public.publish_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',5)) FROM editorial_fixture$q$,
  'PT409','EDITORIAL_APPROVAL_REQUIRED','Changed revision needs renewed approval');
SELECT is((SELECT public.approve_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',5))->>'approvedRevision'
  FROM editorial_fixture),'5','New approval is explicit');
RESET ROLE;
UPDATE public.track_sources SET verified_at=now()-interval '2 hours'
WHERE track_id=(SELECT tracks[1] FROM editorial_fixture);
SET LOCAL ROLE authenticated;
SELECT throws_ok($q$SELECT public.publish_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',5)) FROM editorial_fixture$q$,
  'PT422','EDITORIAL_NOT_READY','Publish rechecks stale Audius evidence');
SELECT is((SELECT public.get_playlist(jsonb_build_object('playlistId',legacy))->>'title' FROM editorial_fixture),
  'BE-03 legacy snapshot','Failed publish leaves old content unchanged');
RESET ROLE;
UPDATE public.track_sources SET verified_at=now() WHERE track_id=(SELECT tracks[1] FROM editorial_fixture);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.publish_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',5))->>'publishedRevision'
  FROM editorial_fixture),'5','Owner publishes valid revision');
SELECT is((SELECT public.publish_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',5))->>'playlistId'
  FROM editorial_fixture),(SELECT legacy::text FROM editorial_fixture),'Publish replay keeps legacy UUID');
SELECT is((SELECT public.get_playlist(jsonb_build_object('playlistId',legacy))#>>'{items,0,track,id}' FROM editorial_fixture),
  (SELECT tracks[2]::text FROM editorial_fixture),'Published order follows draft array');
SELECT is((SELECT public.unpublish_editorial_playlist(jsonb_build_object('placeId',tokyo,'expectedRevision',5))->>'revision'
  FROM editorial_fixture),'6','Unpublishing advances revision');
SELECT throws_ok($q$SELECT public.publish_editorial_draft(jsonb_build_object('placeId',tokyo,'expectedRevision',6)) FROM editorial_fixture$q$,
  'PT409','EDITORIAL_APPROVAL_REQUIRED','Unpublished data requires new approval to reappear');
RESET ROLE;
SELECT ok(internal.media_referenced('editorial/covers/be03-pgtap.png'),
  'Referenced artwork is protected by the Storage policy and delete guard');
UPDATE public.places p SET title='Previous city title' FROM editorial_fixture f WHERE p.id=f.tokyo;
INSERT INTO public.places(id,title,country_code,geom,is_published)
SELECT unknown,'Tokyo','JP',extensions.ST_GeogFromText('POINT(139.69 35.69)'),true FROM editorial_fixture;
SELECT set_config('request.jwt.claim.sub',(SELECT editor::text FROM editorial_fixture),true);
SET LOCAL ROLE authenticated;
SELECT throws_ok($q$SELECT public.save_editorial_draft(jsonb_build_object('placeId',unknown,'expectedRevision',0)) FROM editorial_fixture$q$,
  'PT409','EDITORIAL_CITY_ALREADY_BOUND','Changing a place label cannot create a second official city draft');
RESET ROLE;

-- Check the second pilot city with a newly imported synthetic catalog and no legacy playlist.
INSERT INTO internal.editorial_fixture_target(marker) VALUES ('de7c0501-2486-4d3a-8fdb-2a6c81d6d342');
CREATE TEMP TABLE london_batch ON COMMIT DROP AS
SELECT jsonb_agg(jsonb_build_object('id','BE03Fixture-sql-london-'||n,
  'artistId','BE03Fixture-sql-artist','title','[BE-03 QA] London '||n,'artist','[BE-03 QA] Artist',
  'durationMs',1000,'permalink','https://audius.co/be03-fixture/sql-london-'||n,
  'canStream',n NOT IN (11,12),'verifiedAt',now()) ORDER BY n) AS items FROM generate_series(1,12) n;
GRANT SELECT ON london_batch TO service_role;
CREATE TEMP TABLE london_import ON COMMIT DROP AS
SELECT id,provider_track_id FROM public.editorial_import_fixture_batch(
  'de7c0501-2486-4d3a-8fdb-2a6c81d6d342',(SELECT items FROM london_batch));
SET LOCAL ROLE service_role;
SELECT throws_ok($q$SELECT public.editorial_import_fixture_batch('ffffffff-ffff-4fff-8fff-ffffffffffff','[]')$q$,
  'PT403','EDITORIAL_FIXTURE_TARGET_DISABLED','Fixture target must match explicitly opted-in DB');
SELECT is((SELECT count(*) FROM public.editorial_import_fixture_batch(
  'de7c0501-2486-4d3a-8fdb-2a6c81d6d342',(SELECT items FROM london_batch))),12::bigint,'Fixture batch replays');
RESET ROLE;
SELECT is((SELECT array_agg(t.track_id ORDER BY t.provider_track_id) FROM public.track_sources t
    WHERE t.provider_track_id LIKE 'BE03Fixture-sql-london-%'),
  (SELECT array_agg(id ORDER BY provider_track_id) FROM london_import),'Replayed provider IDs retain UUIDs');
CREATE TEMP TABLE london_draft ON COMMIT DROP AS
SELECT jsonb_build_object('placeId',london,'expectedRevision',0,'title','[BE-03 QA] Лондон',
  'description','Тестовое описание Лондона','coverPath','editorial/covers/be03-pgtap.png',
  'genreIds',ARRAY[32001],'tagIds',ARRAY[32001],
  'trackIds',(SELECT array_agg(id ORDER BY provider_track_id) FROM london_import)) AS input FROM editorial_fixture;
GRANT SELECT ON london_draft TO authenticated;
CREATE TEMP TABLE london_publication(id uuid) ON COMMIT DROP;
GRANT SELECT, INSERT ON london_publication TO authenticated;
GRANT SELECT ON london_publication TO anon;
SELECT set_config('request.jwt.claim.sub',(SELECT editor::text FROM editorial_fixture),true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.save_editorial_draft(input)->>'revision' FROM london_draft),'1','London draft created');
SELECT set_config('request.jwt.claim.sub',(SELECT owner::text FROM editorial_fixture),true);
SELECT is((SELECT public.approve_editorial_draft(jsonb_build_object('placeId',london,'expectedRevision',1))->>'approvedRevision'
  FROM editorial_fixture),'1','Owner approves London');
INSERT INTO london_publication SELECT (public.publish_editorial_draft(
  jsonb_build_object('placeId',london,'expectedRevision',1))->>'playlistId')::uuid FROM editorial_fixture;
RESET ROLE;

SELECT set_config('request.jwt.claim.sub','',true);
SET LOCAL ROLE anon;
SELECT throws_ok($q$SELECT public.get_playlist(jsonb_build_object('playlistId',legacy)) FROM editorial_fixture$q$,
  'PT404','PLAYLIST_NOT_FOUND','Guest cannot read the unpublished official playlist');
SELECT is((SELECT jsonb_array_length(public.get_playlist(jsonb_build_object('playlistId',id))->'items') FROM london_publication),
  12,'Guest sees twelve controlled London tracks');
SELECT is((SELECT item->>'playlistId' FROM jsonb_array_elements(public.get_visible_places(
    '{"minLon":-1,"maxLon":1,"minLat":51,"maxLat":52}')->'items') item
    WHERE item->>'id'=(SELECT london::text FROM editorial_fixture)),
  (SELECT id::text FROM london_publication),'Guest map links London to the publication');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
