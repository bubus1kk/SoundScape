BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(34);

CREATE TEMP TABLE fixture ON COMMIT DROP AS SELECT
  gen_random_uuid() AS owner_id, gen_random_uuid() AS reader_id,
  gen_random_uuid() AS playlist_id, gen_random_uuid() AS hidden_id,
  gen_random_uuid() AS copy_id, gen_random_uuid() AS pin_id,
  gen_random_uuid() AS event_id, gen_random_uuid() AS place_id, gen_random_uuid() AS journey_id,
  gen_random_uuid() AS track_a, gen_random_uuid() AS track_b,
  gen_random_uuid() AS request_id;
GRANT SELECT ON fixture TO anon, authenticated;
INSERT INTO auth.users (id, raw_user_meta_data)
SELECT owner_id, '{"role":"ADMIN"}'::jsonb FROM fixture
UNION ALL SELECT reader_id, '{}'::jsonb FROM fixture;
INSERT INTO public.tracks (id, title, duration_ms)
SELECT track_a, 'A', 1000 FROM fixture UNION ALL SELECT track_b, 'B', 2000 FROM fixture;
INSERT INTO public.track_sources(track_id, provider, provider_track_id, can_stream, verified_at)
SELECT track_a, 'AUDIUS', 'be02-test-fixture', true, now() FROM fixture;
INSERT INTO public.playlists (id, owner_id, title, visibility)
SELECT playlist_id, owner_id, 'Public', 'PUBLIC' FROM fixture
UNION ALL SELECT hidden_id, owner_id, 'Hidden', 'UNLISTED' FROM fixture;
INSERT INTO public.playlist_tracks (playlist_id, track_id, position)
SELECT playlist_id, track_b, 1 FROM fixture UNION ALL SELECT playlist_id, track_a, 2 FROM fixture;
INSERT INTO public.music_pins (id, owner_id, title, visibility, geom)
SELECT pin_id, owner_id, 'Pin', 'PUBLIC', extensions.ST_GeogFromText('POINT(40 55)') FROM fixture;
INSERT INTO storage.objects(bucket_id,name)
SELECT 'soundscape-media', 'users/' || owner_id || '/private.png' FROM fixture;
INSERT INTO public.places(id, title, country_code, geom, is_published, cover_path)
SELECT place_id, 'Place', 'FI', extensions.ST_GeogFromText('POINT(24 60)'), true,
  'users/' || owner_id || '/private.png' FROM fixture;
INSERT INTO public.share_tokens(owner_id, playlist_id, token_hash, created_at, expires_at)
SELECT owner_id, hidden_id, repeat('a',64), now()-interval '2 days', now()-interval '1 day' FROM fixture;
INSERT INTO internal.api_quota_counters(scope_type, subject_key, operation_code, window_started_at)
SELECT 'USER', owner_id::text, 'GENERATE', now() FROM fixture;

SELECT is((SELECT role_code FROM public.profiles WHERE id = (SELECT owner_id FROM fixture)), 'USER',
  'Signup metadata cannot assign a role');
SELECT lives_ok($$SELECT internal.initialize_profile(owner_id) FROM fixture$$, 'Profile initialization can be retried');
SELECT ok(NOT has_function_privilege('authenticated','public.edge_begin_account_deletion(uuid)','EXECUTE'),
  'Account deletion SQL is service-only');
SELECT ok(NOT has_function_privilege('authenticated','internal.save_playlist(jsonb)','EXECUTE'),
  'The existing mutation implementation cannot be invoked directly');
SELECT is(public.edge_open_share(repeat('a',64)), NULL::jsonb, 'Expired UNLISTED token cannot be opened');
SELECT is((SELECT public.edge_media_path(NULL,'PLACE',place_id) FROM fixture), NULL::jsonb,
  'Published place cannot expose a user-owned file through its cover path');

SET LOCAL ROLE anon;
SELECT is((SELECT public.get_public_profile(jsonb_build_object('profileId',owner_id))->>'id' FROM fixture),
  (SELECT owner_id::text FROM fixture), 'Guest opens a public profile by UUID');
SELECT ok((SELECT NOT (public.get_public_profile(jsonb_build_object('profileId',owner_id)) ?| ARRAY['email','role','avatarPath']) FROM fixture),
  'Public profile exposes no private fields');
SELECT is((SELECT jsonb_array_length(public.list_public_profile_items(
  jsonb_build_object('profileId',owner_id,'kind','PLAYLIST'))->'items') FROM fixture), 1,
  'Public profile excludes unlisted playlists');
SELECT throws_ok($$SELECT public.social_state(jsonb_build_object('kind','PLAYLIST_LIKE','targetId',playlist_id,'active',true))
  FROM fixture$$,'PT401','AUTH_REQUIRED','Guest cannot like a playlist');

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', (SELECT owner_id::text FROM fixture), true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.list_my_music_pins('{"limit":1}')#>>'{items,0,id}'),
  (SELECT pin_id::text FROM fixture), 'Owner pin list returns their own record');
RESET ROLE;
SELECT set_config('request.jwt.claim.sub', (SELECT reader_id::text FROM fixture), true);
SET LOCAL ROLE authenticated;
SELECT is(jsonb_array_length(public.list_my_music_pins('{}')->'items'), 0, 'Foreign pins are excluded from the personal list');
SELECT is((SELECT public.social_state(jsonb_build_object('kind','PLAYLIST_LIKE','targetId',playlist_id,'active',true))->>'count' FROM fixture),
  '1', 'Like sets state and returns count');
SELECT is((SELECT public.social_state(jsonb_build_object('kind','PLAYLIST_LIKE','targetId',playlist_id,'active',true))->>'count' FROM fixture),
  '1', 'Repeating a like is idempotent');
SELECT throws_ok($$INSERT INTO public.playlist_likes(user_id,playlist_id) SELECT owner_id,playlist_id FROM fixture$$,
  '42501', NULL::text, 'Direct REST grant cannot create another user’s like');
SELECT throws_ok($$SELECT public.social_state(jsonb_build_object('kind','PLAYLIST_LIKE','targetId',hidden_id,'active',true))
  FROM fixture$$,'PT404','SOCIAL_TARGET_NOT_FOUND','Unlisted playlist cannot receive likes by UUID');
SELECT is((SELECT public.social_state(jsonb_build_object('kind','PIN_LIKE','targetId',pin_id,'active',true))->>'count'
  FROM fixture),'1','Public music pin can receive likes');
SELECT is((SELECT public.social_state(jsonb_build_object('kind','FAVORITE','targetId',track_a,'active',true))->>'active'
  FROM fixture),'true','Favorite uses canonical UUID');
SELECT is((SELECT jsonb_array_length(public.list_my_collection('{"kind":"FAVORITE","limit":1}')->'items')),
  1,'Favorites list returns exactly one catalog track');
SELECT is((SELECT public.social_state(jsonb_build_object('kind','SAVED_PLAYLIST','targetId',playlist_id,'active',true))->>'active'
  FROM fixture),'true','User saves a library reference');
SELECT is((SELECT public.copy_public_playlist(jsonb_build_object('sourcePlaylistId',playlist_id,'playlistId',copy_id,
  'idempotencyKey',request_id))#>>'{items,0,track,id}' FROM fixture),
  (SELECT track_b::text FROM fixture),'Independent copy preserves first track');
SELECT is((SELECT public.copy_public_playlist(jsonb_build_object('sourcePlaylistId',playlist_id,'playlistId',copy_id,
  'idempotencyKey',request_id))->>'visibility' FROM fixture),'PRIVATE','Copy replay returns the original result');
SELECT throws_ok($$SELECT public.copy_public_playlist(jsonb_build_object('sourcePlaylistId',playlist_id,'idempotencyKey',request_id))
  FROM fixture$$,'PT409','IDEMPOTENCY_KEY_REUSED','Changed payload with same key is rejected');

RESET ROLE;
UPDATE public.playlists SET visibility = 'PRIVATE' WHERE id = (SELECT playlist_id FROM fixture);
SELECT set_config('request.jwt.claim.sub', (SELECT reader_id::text FROM fixture), true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*) FROM public.saved_playlists), 0::bigint, 'Hidden library target is unavailable through direct RLS');
SELECT is((SELECT jsonb_array_length(public.list_my_collection('{"kind":"SAVED_PLAYLIST"}')->'items')),
  0,'Hidden library target is unavailable through RPC');
SELECT is((SELECT public.get_playlist(jsonb_build_object('playlistId',copy_id))->>'ownerId' FROM fixture),
  (SELECT reader_id::text FROM fixture),'Copy remains owned by and available to the reader');
SELECT is((SELECT public.save_music_journey(jsonb_build_object('id',journey_id,'title','Route',
  'playlistId',copy_id,'visibility','PRIVATE','stops',jsonb_build_array(
    jsonb_build_object('kind','PIN','id',pin_id,'position',1),
    jsonb_build_object('kind','PLACE','id',place_id,'position',2))))->>'durationMs' FROM fixture),
  '3000','Journey duration includes both catalog tracks');
SELECT is((SELECT public.get_music_journey(jsonb_build_object('id',journey_id))->>'inAppDurationMs' FROM fixture),
  '1000','Playable duration excludes external-only tracks');
SELECT is((SELECT public.get_music_journey(jsonb_build_object('id',journey_id))#>>'{stops,1,id}' FROM fixture),
  (SELECT place_id::text FROM fixture),'Journey preserves stop order');
SELECT is((SELECT public.save_travel_event(jsonb_build_object('id',event_id,'title','Diary','musicPinId',pin_id,
  'eventDate','2026-11-24','trackIds',jsonb_build_array(track_a)))->>'visibility' FROM fixture),
  'PRIVATE','Diary is private by default');
SELECT is((SELECT public.list_travel_events('{"dateFrom":"2026-11-24","dateTo":"2026-11-24","locationQuery":"pin","mapOnly":true}')
  #>>'{items,0,mapLatitude}'),'55','Diary finds accessible linked location and map coordinates');
RESET ROLE;
DELETE FROM auth.users WHERE id = (SELECT owner_id FROM fixture);
SELECT ok((SELECT NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = f.owner_id)
    AND EXISTS (SELECT 1 FROM public.travel_events WHERE id = f.event_id AND music_pin_id IS NULL AND revision = 2)
    AND EXISTS (SELECT 1 FROM public.tracks WHERE id = f.track_a) FROM fixture f),
  'Account cascade detaches foreign diary and preserves catalog');
SELECT is((SELECT j.revision FROM public.music_journeys j JOIN fixture f ON j.id=f.journey_id),
  2,'Removing a foreign stop advances the journey revision');
SELECT is((SELECT count(*) FROM internal.api_quota_counters q JOIN fixture f ON q.subject_key=f.owner_id::text),
  0::bigint, 'Account deletion clears its per-user quota records');
SELECT * FROM finish();
ROLLBACK;
