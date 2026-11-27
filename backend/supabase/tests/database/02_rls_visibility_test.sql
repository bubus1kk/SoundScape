-- SoundScape Practice 11. LOCAL database only. No cloud/production invocation.
-- The Supabase CLI pgTAP runner uses a transaction per test file.
-- All fixture data is rolled back; do not use --linked or --db-url.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(24);

CREATE TEMP TABLE ss_rls_ctx ON COMMIT DROP AS
SELECT
    gen_random_uuid() AS owner_id,
    gen_random_uuid() AS viewer_id,
    gen_random_uuid() AS editor_id,
    gen_random_uuid() AS admin_id,
    gen_random_uuid() AS draft_city,
    gen_random_uuid() AS fixture_track,
    gen_random_uuid() AS pl_public,
    gen_random_uuid() AS pl_unlisted,
    gen_random_uuid() AS pl_private,
    gen_random_uuid() AS pin_public,
    gen_random_uuid() AS pin_unlisted,
    gen_random_uuid() AS pin_private,
    gen_random_uuid() AS ev_public,
    gen_random_uuid() AS ev_unlisted,
    gen_random_uuid() AS ev_private,
    gen_random_uuid() AS journey_public,
    gen_random_uuid() AS journey_unlisted,
    gen_random_uuid() AS journey_private;

-- A second role can read this session-only table to look up fixture identifiers.
GRANT SELECT ON ss_rls_ctx TO anon, authenticated;

-- Real (transactional) auth users are required because profiles references auth.users.
-- The production migration trigger creates their corresponding public.profiles rows.
INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
SELECT owner_id, 'authenticated', 'authenticated', 'pr11-' || owner_id::text || '@example.invalid', '', now(), now(), now()
FROM ss_rls_ctx
UNION ALL
SELECT viewer_id, 'authenticated', 'authenticated', 'pr11-' || viewer_id::text || '@example.invalid', '', now(), now(), now()
FROM ss_rls_ctx
UNION ALL
SELECT id, 'authenticated', 'authenticated', 'pr11-' || id::text || '@example.invalid', '', now(), now(), now()
FROM ss_rls_ctx CROSS JOIN LATERAL (VALUES (editor_id), (admin_id)) AS accounts(id);

UPDATE public.profiles SET role_code = 'EDITOR' WHERE id = (SELECT editor_id FROM ss_rls_ctx);
UPDATE public.profiles SET role_code = 'ADMIN' WHERE id = (SELECT admin_id FROM ss_rls_ctx);
INSERT INTO public.places (id, title, country_code, geom)
SELECT draft_city, 'Unpublished local fixture', 'RU', extensions.ST_GeogFromText('SRID=4326;POINT(44 56)')
FROM ss_rls_ctx;
INSERT INTO public.tracks (id, title, duration_ms)
SELECT fixture_track, 'Local photo-test track', 1000 FROM ss_rls_ctx;

INSERT INTO public.playlists (id, owner_id, title, visibility)
SELECT ids.id, c.owner_id, 'SS_PR11_' || ids.visibility, ids.visibility
FROM ss_rls_ctx c CROSS JOIN LATERAL (VALUES
    (c.pl_public, 'PUBLIC'), (c.pl_unlisted, 'UNLISTED'), (c.pl_private, 'PRIVATE')
) AS ids(id, visibility);

INSERT INTO public.music_pins (id, owner_id, title, geom, visibility)
SELECT ids.id, c.owner_id, 'SS_PR11_' || ids.visibility,
       extensions.ST_GeogFromText('SRID=4326;POINT(44 56)'), ids.visibility
FROM ss_rls_ctx c CROSS JOIN LATERAL (VALUES
    (c.pin_public, 'PUBLIC'), (c.pin_unlisted, 'UNLISTED'), (c.pin_private, 'PRIVATE')
) AS ids(id, visibility);

INSERT INTO public.travel_events (id, owner_id, title, event_date, location_name, visibility)
SELECT ids.id, c.owner_id, 'SS_PR11_' || ids.visibility,
       CURRENT_DATE, 'Local test location', ids.visibility
FROM ss_rls_ctx c CROSS JOIN LATERAL (VALUES
    (c.ev_public, 'PUBLIC'), (c.ev_unlisted, 'UNLISTED'), (c.ev_private, 'PRIVATE')
) AS ids(id, visibility);

INSERT INTO public.music_journeys (id, owner_id, title, visibility)
SELECT ids.id, c.owner_id, 'SS_PR11_' || ids.visibility, ids.visibility
FROM ss_rls_ctx c CROSS JOIN LATERAL (VALUES
    (c.journey_public, 'PUBLIC'), (c.journey_unlisted, 'UNLISTED'), (c.journey_private, 'PRIVATE')
) AS ids(id, visibility);

-- Identity: owner.
RESET ROLE;
SELECT set_config('request.jwt.claim.sub', (SELECT owner_id::text FROM pg_temp.ss_rls_ctx), true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::integer FROM public.playlists x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.pl_public, c.pl_unlisted, c.pl_private)), 3,
          'owner sees 3 of 3 playlists records');
SELECT is((SELECT count(*)::integer FROM public.music_pins x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.pin_public, c.pin_unlisted, c.pin_private)), 3,
          'owner sees 3 of 3 music_pins records');
SELECT is((SELECT count(*)::integer FROM public.travel_events x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.ev_public, c.ev_unlisted, c.ev_private)), 3,
          'owner sees 3 of 3 travel_events records');
SELECT is((SELECT count(*)::integer FROM public.music_journeys x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.journey_public, c.journey_unlisted, c.journey_private)), 3,
          'owner sees 3 of 3 music_journeys records');
SELECT results_eq(
    $$UPDATE public.profiles SET username = username || '_ok'
      WHERE id = (SELECT owner_id FROM pg_temp.ss_rls_ctx) RETURNING id$$,
    $$SELECT owner_id FROM pg_temp.ss_rls_ctx$$,
    'Owner may update own username');

INSERT INTO storage.objects (bucket_id, name)
SELECT 'soundscape-media', 'users/' || owner_id::text || '/photo-' || n::text || '.jpg'
FROM pg_temp.ss_rls_ctx CROSS JOIN generate_series(1, 5) AS n;
SELECT is((SELECT count(*)::integer FROM storage.objects
           WHERE name LIKE 'users/' || (SELECT owner_id FROM pg_temp.ss_rls_ctx)::text || '/photo-%'), 5,
          'Owner can create and read five private photo objects');
SELECT is(jsonb_array_length(public.save_music_pin(jsonb_build_object(
    'title', 'Five photos', 'visibility', 'PRIVATE', 'latitude', 56, 'longitude', 44,
    'trackIds', (SELECT jsonb_build_array(fixture_track) FROM pg_temp.ss_rls_ctx),
    'photoPaths', (SELECT jsonb_agg(name) FROM storage.objects
                   WHERE name LIKE 'users/' || (SELECT owner_id FROM pg_temp.ss_rls_ctx)::text || '/photo-%')
))->'photoPaths'), 5, 'Music PIN accepts five distinct photos');
SELECT throws_ok(
    $$SELECT public.save_music_pin(jsonb_build_object(
        'title','Too many','visibility','PRIVATE','latitude',56,'longitude',44,
        'trackIds',(SELECT jsonb_build_array(fixture_track) FROM pg_temp.ss_rls_ctx),
        'photoPaths','["1","2","3","4","5","6"]'::jsonb
    ))$$,
    'PT422', 'INVALID_PHOTO_COUNT', 'Music PIN rejects more than five photos');
SELECT throws_ok(
    $$SELECT public.save_travel_event(jsonb_build_object(
        'title','Too many','visibility','PRIVATE','eventDate',CURRENT_DATE,'locationName','Local fixture',
        'trackIds',(SELECT jsonb_build_array(fixture_track) FROM pg_temp.ss_rls_ctx),
        'photoPaths',(SELECT jsonb_agg(n::text) FROM generate_series(1,11) AS n)
    ))$$,
    'PT422', 'INVALID_PHOTO_COUNT', 'Travel diary rejects more than ten photos');
SELECT throws_ok(
    $$SELECT public.save_music_pin(jsonb_build_object(
        'title','Duplicates','visibility','PRIVATE','latitude',56,'longitude',44,
        'trackIds',(SELECT jsonb_build_array(fixture_track) FROM pg_temp.ss_rls_ctx),
        'photoPaths','["duplicate","duplicate"]'::jsonb
    ))$$,
    'PT422', 'DUPLICATE_PHOTO_PATH', 'Music PIN rejects duplicate photo paths');

-- Identity: viewer.
RESET ROLE;
SELECT set_config('request.jwt.claim.sub', (SELECT viewer_id::text FROM pg_temp.ss_rls_ctx), true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::integer FROM public.playlists x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.pl_public, c.pl_unlisted, c.pl_private)), 1,
          'viewer sees 1 of 3 playlists records');
SELECT is((SELECT count(*)::integer FROM public.music_pins x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.pin_public, c.pin_unlisted, c.pin_private)), 1,
          'viewer sees 1 of 3 music_pins records');
SELECT is((SELECT count(*)::integer FROM public.travel_events x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.ev_public, c.ev_unlisted, c.ev_private)), 1,
          'viewer sees 1 of 3 travel_events records');
SELECT is((SELECT count(*)::integer FROM public.music_journeys x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.journey_public, c.journey_unlisted, c.journey_private)), 1,
          'viewer sees 1 of 3 music_journeys records');
SELECT is_empty(
    $$UPDATE public.profiles SET username = username || '_bad'
      WHERE id = (SELECT owner_id FROM pg_temp.ss_rls_ctx) RETURNING id$$,
    'Second user cannot update owner profile');
SELECT is((SELECT count(*)::integer FROM storage.objects
           WHERE name LIKE 'users/' || (SELECT owner_id FROM pg_temp.ss_rls_ctx)::text || '/photo-%'), 0,
          'Unrelated user cannot read private photo objects');
SELECT is((SELECT count(*)::integer FROM public.places WHERE id = (SELECT draft_city FROM pg_temp.ss_rls_ctx)), 0,
          'Ordinary user cannot read an unpublished city');
SELECT throws_ok(
    $$INSERT INTO storage.objects (bucket_id,name) VALUES ('soundscape-media','editorial/rls-no.jpg')$$,
    '42501', NULL::text, 'Ordinary user cannot write editorial storage');

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', (SELECT editor_id::text FROM pg_temp.ss_rls_ctx), true);
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*)::integer FROM public.places WHERE id = (SELECT draft_city FROM pg_temp.ss_rls_ctx)), 1,
          'Editor can read an unpublished city');
RESET ROLE;
SELECT set_config('request.jwt.claim.sub', (SELECT admin_id::text FROM pg_temp.ss_rls_ctx), true);
SET LOCAL ROLE authenticated;
SELECT lives_ok(
    $$INSERT INTO storage.objects (bucket_id,name)
      VALUES ('soundscape-media','editorial/rls-' || auth.uid()::text || '.jpg')$$,
    'Administrator can write editorial storage');

-- Identity: guest.
RESET ROLE;
SELECT set_config('request.jwt.claim.sub', '', true);
SET LOCAL ROLE anon;
SELECT is((SELECT count(*)::integer FROM public.playlists x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.pl_public, c.pl_unlisted, c.pl_private)), 1,
          'guest sees 1 of 3 playlists records');
SELECT is((SELECT count(*)::integer FROM public.music_pins x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.pin_public, c.pin_unlisted, c.pin_private)), 1,
          'guest sees 1 of 3 music_pins records');
SELECT is((SELECT count(*)::integer FROM public.travel_events x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.ev_public, c.ev_unlisted, c.ev_private)), 1,
          'guest sees 1 of 3 travel_events records');
SELECT is((SELECT count(*)::integer FROM public.music_journeys x CROSS JOIN pg_temp.ss_rls_ctx c
           WHERE x.id IN (c.journey_public, c.journey_unlisted, c.journey_private)), 1,
          'guest sees 1 of 3 music_journeys records');

RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
