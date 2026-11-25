BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(16);

CREATE TEMP TABLE close_fixture ON COMMIT DROP AS
SELECT gen_random_uuid() AS owner, gen_random_uuid() AS traveler,
  gen_random_uuid() AS first_pin, gen_random_uuid() AS second_pin, gen_random_uuid() AS place,
  gen_random_uuid() AS journey, gen_random_uuid() AS shared_journey;
GRANT SELECT ON close_fixture TO anon, authenticated;
INSERT INTO auth.users(id) SELECT owner FROM close_fixture UNION ALL SELECT traveler FROM close_fixture;
INSERT INTO public.places(id,title,country_code,geom,is_published)
SELECT place,'A real remaining stop','FI',extensions.ST_GeogFromText('POINT(24 60)'),true FROM close_fixture;
INSERT INTO public.music_pins(id,owner_id,title,visibility,geom)
SELECT first_pin,owner,'First pin content','PUBLIC',extensions.ST_GeogFromText('POINT(25 60)') FROM close_fixture
UNION ALL SELECT second_pin,owner,'Second pin content','PUBLIC',extensions.ST_GeogFromText('POINT(26 60)') FROM close_fixture;
INSERT INTO public.music_journeys(id,owner_id,title,visibility)
SELECT journey,traveler,'Public route','PUBLIC' FROM close_fixture
UNION ALL SELECT shared_journey,traveler,'Shared route','UNLISTED' FROM close_fixture;
INSERT INTO public.journey_places(journey_id,pin_id,place_id,position)
SELECT journey,first_pin,NULL::uuid,1 FROM close_fixture UNION ALL
SELECT journey,second_pin,NULL::uuid,2 FROM close_fixture UNION ALL
SELECT journey,NULL::uuid,place,3 FROM close_fixture UNION ALL
SELECT shared_journey,second_pin,NULL::uuid,1 FROM close_fixture UNION ALL
SELECT shared_journey,NULL::uuid,place,2 FROM close_fixture;
INSERT INTO public.share_tokens(owner_id,journey_id,token_hash)
SELECT traveler,shared_journey,repeat('b',64) FROM close_fixture;

SELECT ok(NOT has_function_privilege('authenticated','public.edge_claim_account_deletion()','EXECUTE'),
  'Only the service credential can claim a pending account deletion');
SELECT is(public.edge_claim_account_deletion(), NULL::uuid, 'No work is invented for an active account');
SELECT is((SELECT public.edge_open_share(repeat('b',64))#>>'{payload,routeStatus}'),
  'READY', 'Shared route has two available stops');

SELECT set_config('request.jwt.claim.sub',(SELECT owner::text FROM close_fixture),true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.delete_music_pin(jsonb_build_object('id',first_pin,'expectedRevision',1))->>'success'
  FROM close_fixture),'true','User may delete a pin referenced by another user');
RESET ROLE;
SELECT is((SELECT public.edge_open_public('JOURNEY',journey)#>>'{payload,routeStatus}' FROM close_fixture),
  'READY','Two remaining public stops keep the route ready');
SELECT is((SELECT public.edge_open_public('JOURNEY',journey)#>>'{payload,stops,0,position}' FROM close_fixture),
  '1','Remaining positions are consecutive in the response');

INSERT INTO internal.account_deletions(user_id) SELECT owner FROM close_fixture;
SELECT is(public.edge_claim_account_deletion(), (SELECT owner FROM close_fixture), 'Pending work is durable without a session');
SELECT is(public.edge_claim_account_deletion(), NULL::uuid, 'A recent worker claim defers another worker');
DELETE FROM auth.users WHERE id=(SELECT owner FROM close_fixture);
SELECT set_config('request.jwt.claim.sub',(SELECT traveler::text FROM close_fixture),true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT public.get_music_journey(jsonb_build_object('id',journey))->>'routeStatus' FROM close_fixture),
  'NEEDS_EDIT','Owner sees their incomplete route');
SELECT is((SELECT public.get_music_journey(jsonb_build_object('id',journey))->>'canGenerate' FROM close_fixture),
  'false','Owner cannot generate from an incomplete route');
SELECT is((SELECT public.get_music_journey(jsonb_build_object('id',journey))->>'revision' FROM close_fixture),
  '3','Explicit pin deletion and account cascade advance revision');
SELECT is((SELECT public.get_music_journey(jsonb_build_object('id',journey))#>>'{stops,0,id}' FROM close_fixture),
  (SELECT place::text FROM close_fixture),'Surviving place remains and the deleted pin is absent');
RESET ROLE;
SELECT is(public.edge_open_share(repeat('b',64)),NULL::jsonb,'Incomplete route is unavailable through its share token');
SELECT set_config('request.jwt.claim.sub','',true);
SET LOCAL ROLE anon;
SELECT throws_ok($$SELECT public.get_music_journey(jsonb_build_object('id',journey)) FROM close_fixture$$,
  'PT404','JOURNEY_NOT_FOUND','Guests cannot open an incomplete route by ID');
SELECT is((SELECT jsonb_array_length(public.list_public_profile_items(
  jsonb_build_object('profileId',traveler,'kind','JOURNEY'))->'items') FROM close_fixture),
  0,'Incomplete route is excluded from the public profile');
SELECT ok((SELECT NOT EXISTS (SELECT 1 FROM jsonb_array_elements(
  public.list_music_journeys('{"scope":"PUBLIC","limit":100}')->'items') AS item
  WHERE item->>'id'=f.journey::text) FROM close_fixture f), 'Incomplete route is excluded from public discovery');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
