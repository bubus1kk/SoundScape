-- SoundScape Practice 11. LOCAL database only. No cloud/production invocation.
-- The Supabase CLI pgTAP runner uses a transaction per test file.
-- All fixture data is rolled back; do not use --linked or --db-url.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(14);

CREATE TEMP TABLE ss_rls_ctx ON COMMIT DROP AS
SELECT
    gen_random_uuid() AS owner_id,
    gen_random_uuid() AS viewer_id,
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
FROM ss_rls_ctx;

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
