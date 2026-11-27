BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path=public,extensions,pg_catalog;
SELECT no_plan();

CREATE TEMP TABLE fix_ids AS SELECT gen_random_uuid() AS user_a,gen_random_uuid() AS user_b,
  gen_random_uuid() AS editor,gen_random_uuid() AS admin,gen_random_uuid() AS publisher,
  gen_random_uuid() AS place,gen_random_uuid() AS playlist,gen_random_uuid() AS personal;
GRANT SELECT ON fix_ids TO anon,authenticated,service_role;
INSERT INTO auth.users(id,raw_app_meta_data)
SELECT user_a,'{}'::jsonb FROM fix_ids UNION ALL SELECT user_b,'{}'::jsonb FROM fix_ids
UNION ALL SELECT editor,'{}'::jsonb FROM fix_ids
UNION ALL SELECT admin,'{}'::jsonb FROM fix_ids
UNION ALL SELECT publisher,'{"soundscape_editorial_owner":true}'::jsonb FROM fix_ids;
UPDATE public.profiles SET role_code='EDITOR' WHERE id=(SELECT editor FROM fix_ids);
UPDATE public.profiles SET role_code='ADMIN' WHERE id IN (SELECT admin FROM fix_ids UNION SELECT publisher FROM fix_ids);
INSERT INTO public.places(id,title,country_code,geom,is_published)
SELECT place,'Токио','JP',extensions.ST_GeogFromText('POINT(139.69 35.69)'),true FROM fix_ids;
INSERT INTO public.genres(id,name) VALUES (32011,'BE-03-FIX QA Genre');
INSERT INTO public.tags(id,name) VALUES (32011,'BE-03-FIX QA Tag');
INSERT INTO storage.objects(bucket_id,name,metadata,owner_id)
SELECT 'soundscape-media','editorial/capitals/v1/tokyo-qa.png','{"size":100,"mimetype":"image/png"}',publisher::text FROM fix_ids;
INSERT INTO public.playlists(id,owner_id,place_id,title,description,cover_path,visibility,is_editorial)
SELECT playlist,publisher,place,'Токио: между последним поездом и рассветом','Историческое описание',
       'editorial/capitals/v1/tokyo-qa.png','PRIVATE',true FROM fix_ids;
INSERT INTO public.playlists(id,owner_id,title,visibility)
SELECT personal,user_a,'Ordinary playlist','PRIVATE' FROM fix_ids;
INSERT INTO public.playlist_genres SELECT playlist,32011 FROM fix_ids;
INSERT INTO public.playlist_tags SELECT playlist,32011 FROM fix_ids;

CREATE TEMP TABLE fix_tracks AS
SELECT n,gen_random_uuid() AS id, CASE WHEN n<=15 THEN 'IN_APP' WHEN n<=28 THEN 'EXTERNAL_ONLY'
  WHEN n=29 THEN 'NO_VERIFIED_SOURCE' ELSE 'IN_APP_RECHECK_REQUIRED' END as state
FROM generate_series(1,30) n;
GRANT SELECT ON fix_tracks TO authenticated;
INSERT INTO public.tracks(id,title,duration_ms) SELECT id,'BE-03-FIX QA '||n,180000 FROM fix_tracks;
INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,can_stream,verified_at)
SELECT id,'AUDIUS','fix-stream-'||n,'https://audius.co/qa/track-'||n,true,
  CASE WHEN n=30 THEN now()-interval '2 hours' ELSE now() END
FROM fix_tracks WHERE state IN ('IN_APP','IN_APP_RECHECK_REQUIRED');
INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,verified_at)
SELECT id,'APPLE_MUSIC',(2000+n)::text,'https://music.apple.com/us/album/qa/2000?i='||(2000+n)||'&uo=4',now()
FROM fix_tracks WHERE state='EXTERNAL_ONLY';
INSERT INTO public.playlist_tracks(playlist_id,track_id,position)
SELECT playlist,id,n-15 FROM fix_ids,fix_tracks WHERE state='EXTERNAL_ONLY';
INSERT INTO public.playlist_tracks(playlist_id,track_id,position)
SELECT personal,id,1 FROM fix_ids,fix_tracks WHERE n=1;

SELECT is((SELECT p.owner_id FROM public.playlists p,fix_ids f WHERE p.id=f.playlist),NULL::uuid,'Official ownership is independent');
SELECT throws_ok($q$INSERT INTO public.playlists(place_id,title,visibility,is_editorial)
  SELECT place,'Second official playlist','PRIVATE',true FROM fix_ids$q$,
  '23505',NULL,'The database rejects a second official playlist for the city');
SELECT is((SELECT internal.editorial_track_state(id) FROM fix_tracks WHERE n=16),'EXTERNAL_ONLY','Apple album track ID and uo are allowed');
SELECT is((SELECT internal.editorial_track_state(id) FROM fix_tracks WHERE n=30),'IN_APP_RECHECK_REQUIRED','Stale Audius remains unresolved');
SELECT is((SELECT internal.editorial_track_state(id) FROM fix_tracks WHERE n=29),'NO_VERIFIED_SOURCE','Unverified source remains unresolved');
SELECT is((SELECT public.edge_media_path(user_b,'PLAYLIST',playlist) FROM fix_ids),NULL::jsonb,
  'An ordinary authenticated account cannot read a private ownerless cover');
SELECT is((SELECT public.edge_media_path(NULL,'PLAYLIST',playlist) FROM fix_ids),NULL::jsonb,
  'A guest cannot read a private ownerless cover');
SELECT is((SELECT public.edge_media_path(editor,'PLAYLIST',playlist)->>'path' FROM fix_ids),
  'editorial/capitals/v1/tokyo-qa.png','An editor can read private official artwork');

SELECT set_config('request.jwt.claim.sub',(SELECT editor::text FROM fix_ids),true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',place,'expectedRevision',0))->>'playlistId'
  FROM fix_ids),(SELECT playlist::text FROM fix_ids),'Adopt a legacy private playlist with same ID');
SELECT is((SELECT public.get_editorial_draft(jsonb_build_object('placeId',place))->>'description' FROM fix_ids),
  'Историческое описание','Adoption preserves description');
SELECT is((SELECT public.check_editorial_draft(jsonb_build_object('placeId',place))->>'externalOnlyCount' FROM fix_ids),
  '13','Adoption preserves all external positions');
RESET ROLE;
SELECT ok(NOT EXISTS(SELECT FROM public.playlists p,fix_ids f WHERE p.id=f.playlist AND p.published_at IS NOT NULL),
  'Adoption does not publish');
SELECT ok((SELECT d.approved_by IS NULL FROM internal.editorial_drafts d,fix_ids f WHERE d.place_id=f.place),'Adoption does not approve');

CREATE TEMP TABLE ratios(in_app integer, external integer, expected boolean);
INSERT INTO ratios VALUES(10,2,true),(9,3,true),(11,2,true),(10,3,true),(13,2,true),(12,3,true),
  (9,2,false),(13,3,false),(13,0,false),(12,1,false),(9,4,false),(0,13,false);
SELECT is(
  (SELECT internal.editorial_issues(jsonb_populate_record(d,jsonb_build_object(
    'track_ids',ARRAY(SELECT id FROM fix_tracks WHERE n<=r.in_app OR n BETWEEN 16 AND 15+r.external ORDER BY n)
  )))='[]' FROM internal.editorial_drafts d WHERE d.place_id=(SELECT place FROM fix_ids)),
  r.expected,'Ratio '||r.in_app||' IN_APP / '||r.external||' EXTERNAL_ONLY')
FROM ratios r;
SELECT ok((SELECT internal.editorial_issues(jsonb_populate_record(d,jsonb_build_object(
  'track_ids',ARRAY(SELECT id FROM fix_tracks WHERE n IN (1,2,3,4,5,6,7,8,9,10,16,17,29))
))) @> '[{"code":"NO_VERIFIED_SOURCE"}]' FROM internal.editorial_drafts d WHERE d.place_id=(SELECT place FROM fix_ids)),
  'Missing source causes an explicit issue');
SELECT ok((SELECT internal.editorial_issues(jsonb_populate_record(d,jsonb_build_object(
  'track_ids',ARRAY(SELECT id FROM fix_tracks WHERE n IN (1,2,3,4,5,6,7,8,9,10,16,17))||(SELECT id FROM fix_tracks WHERE n=1)
))) @> '[{"code":"DUPLICATE_TRACK"}]' FROM internal.editorial_drafts d WHERE d.place_id=(SELECT place FROM fix_ids)),
  'Duplicate track still fails');

SELECT set_config('request.jwt.claim.sub',(SELECT user_a::text FROM fix_ids),true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.save_playlist(jsonb_build_object('title','A private single-track playlist',
  'visibility','PRIVATE','trackIds',ARRAY[(SELECT id FROM fix_tracks WHERE n=1)]))->>'visibility'),
  'PRIVATE','Ordinary playlists retain their original contract');
RESET ROLE;
SELECT set_config('request.jwt.claim.sub',(SELECT publisher::text FROM fix_ids),true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',place,'expectedRevision',1,
   'trackIds',ARRAY(SELECT id FROM fix_tracks WHERE n BETWEEN 1 AND 11 OR n IN (16,17) ORDER BY n)))->>'revision' FROM fix_ids),
  '2','Replace legacy composition without replacing metadata');
SELECT is((SELECT public.save_editorial_draft(jsonb_build_object('placeId',place,'expectedRevision',0))->>'revision' FROM fix_ids),
  '2','Repeated initialization reads the latest draft without overwriting it');
SELECT is((SELECT public.approve_editorial_draft(jsonb_build_object('placeId',place,'expectedRevision',2))->>'approvedRevision' FROM fix_ids),
  '2','Owner approves reworked draft explicitly');
SELECT is((SELECT public.publish_editorial_draft(jsonb_build_object('placeId',place,'expectedRevision',2))->>'playlistId' FROM fix_ids),
  (SELECT playlist::text FROM fix_ids),'Publishing preserves official UUID');
RESET ROLE;

SELECT is((SELECT public.edge_media_path(NULL,'PLACE',place)->>'path' FROM fix_ids),
  'editorial/capitals/v1/tokyo-qa.png','Published PLACE media resolves the existing official object');
SELECT is((SELECT p->>'coverPath' FROM jsonb_array_elements(public.get_visible_places(
  '{"minLat":35,"maxLat":36,"minLon":139,"maxLon":140}')->'items') p
  WHERE p->>'id'=(SELECT place::text FROM fix_ids)),'editorial/capitals/v1/tokyo-qa.png','Map returns existing cover path');
SELECT public.edge_begin_account_deletion(publisher) FROM fix_ids;
SELECT ok((SELECT public.edge_finish_account_media(publisher) FROM fix_ids),'Publisher media is released by account cleanup');
DELETE FROM auth.users WHERE id IN (SELECT user_a FROM fix_ids UNION SELECT editor FROM fix_ids UNION SELECT admin FROM fix_ids
  UNION SELECT publisher FROM fix_ids);
SELECT ok(NOT EXISTS(SELECT FROM public.profiles WHERE id=(SELECT publisher FROM fix_ids)),'Publisher profile is removed');
SELECT ok((SELECT d.approved_by IS NULL AND d.published_revision=2
  FROM internal.editorial_drafts d,fix_ids f WHERE d.place_id=f.place),
  'Publisher approval identity is cleared without losing the published revision');
SELECT ok(NOT EXISTS(SELECT FROM public.playlists WHERE id=(SELECT personal FROM fix_ids)),'Personal deletion still cascades');
SELECT ok(EXISTS(SELECT FROM public.playlists p,fix_ids f WHERE p.id=f.playlist AND p.visibility='PUBLIC' AND p.place_id=f.place),
  'Official playlist and city survive USER, EDITOR, ADMIN and publisher deletion');
SELECT is((SELECT count(*) FROM public.playlist_tracks t,fix_ids f WHERE t.playlist_id=f.playlist),13::bigint,'Ordered publication tracks survive deletion');
SELECT is((SELECT public.edge_media_path(NULL,'PLAYLIST',playlist)->>'path' FROM fix_ids),
  'editorial/capitals/v1/tokyo-qa.png','Published artwork survives publisher removal');
SELECT ok(NOT has_function_privilege('authenticated','public.editorial_import_verified_batch(uuid,jsonb)','EXECUTE'),
  'Editors cannot call the verified source infrastructure');
SELECT ok(NOT has_function_privilege('service_role','public.publish_editorial_draft(jsonb)','EXECUTE'),
  'Importer credentials cannot publish');
SELECT throws_ok($q$SELECT public.editorial_import_verified_batch('ffffffff-ffff-4fff-8fff-ffffffffffff','[]')$q$,
  'PT403','EDITORIAL_LIVE_TARGET_DISABLED','Unarmed environment cannot ingest live manifests');
INSERT INTO internal.editorial_fixture_target(marker,allow_live)
VALUES ('de7c0501-2486-4d3a-8fdb-2a6c81d6d346',true);
INSERT INTO public.artists(id,name) VALUES('de7c0501-2486-4d3a-8fdb-2a6c81d6d347','QA Matcher');
INSERT INTO public.track_artists(track_id,artist_id,credit_order)
SELECT id,'de7c0501-2486-4d3a-8fdb-2a6c81d6d347',1 FROM fix_tracks WHERE n=16;
UPDATE public.tracks SET isrc='USAAA2400001' WHERE id=(SELECT id FROM fix_tracks WHERE n=16);
CREATE TEMP TABLE verified_batch AS SELECT jsonb_build_array(jsonb_build_object(
  'id','qa-exact-recording','canonicalTrackId',id,'title','BE-03-FIX QA 16','artist','QA Matcher',
  'isrc','USAAA2400001','durationMs',180000,'permalink','https://audius.co/qa/exact-recording',
  'verifiedAt',now(),'canStream',true)) AS data FROM fix_tracks WHERE n=16;
SELECT is((SELECT p.id FROM public.editorial_import_verified_batch(
  'de7c0501-2486-4d3a-8fdb-2a6c81d6d346',(SELECT data FROM verified_batch)) p),
  (SELECT id FROM fix_tracks WHERE n=16),'Adding verified Audius source keeps the SoundScape UUID');
SELECT is((SELECT p.id FROM public.editorial_import_verified_batch(
  'de7c0501-2486-4d3a-8fdb-2a6c81d6d346',(SELECT data FROM verified_batch)) p),
  (SELECT id FROM fix_tracks WHERE n=16),'Verified import replay is idempotent');
SELECT is((SELECT count(*) FROM public.track_sources WHERE track_id=(SELECT id FROM fix_tracks WHERE n=16)),
  2::bigint,'Original Apple Music source remains attached');
SELECT throws_ok($q$SELECT public.editorial_import_verified_batch(
  'de7c0501-2486-4d3a-8fdb-2a6c81d6d346',(SELECT jsonb_set(jsonb_set(data,'{0,id}','"qa-other-recording"'),
  '{0,title}','"Different version"') FROM verified_batch))$q$,
  'PT422','EDITORIAL_CANONICAL_IDENTITY_UNCONFIRMED','A different recording cannot be attached to the canonical UUID');
SELECT * FROM finish();
ROLLBACK;
