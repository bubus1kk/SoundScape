-- SoundScape Practice 11. LOCAL database only. No cloud/production invocation.
-- The Supabase CLI pgTAP runner uses a transaction per test file.
-- All fixture data is rolled back; do not use --linked or --db-url.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(12);

CREATE TEMP TABLE ss_integrity_ctx ON COMMIT DROP AS
SELECT gen_random_uuid() owner_id, gen_random_uuid() viewer_id, gen_random_uuid() playlist_id;

INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
SELECT owner_id, 'authenticated', 'authenticated', 'pr11-' || owner_id::text || '@example.invalid', '', now(), now(), now()
FROM ss_integrity_ctx
UNION ALL
SELECT viewer_id, 'authenticated', 'authenticated', 'pr11-' || viewer_id::text || '@example.invalid', '', now(), now(), now()
FROM ss_integrity_ctx;

INSERT INTO public.playlists (id, owner_id, title, visibility)
SELECT playlist_id, owner_id, 'SS_PR11_integrity', 'PRIVATE' FROM ss_integrity_ctx;

SELECT throws_ok(
    $query$INSERT INTO public.playlists (owner_id, title, visibility) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), '   ', 'PRIVATE')$query$,
    '23514', NULL::text,
    'Playlist rejects blank title'
);

SELECT throws_ok(
    $query$INSERT INTO public.playlists (owner_id, title, visibility) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), 'Good', 'UNKNOWN')$query$,
    '23514', NULL::text,
    'Playlist rejects unknown visibility'
);

SELECT throws_ok(
    $query$INSERT INTO public.playlists (owner_id, title, visibility, revision) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), 'Good', 'PRIVATE', 0)$query$,
    '23514', NULL::text,
    'Playlist revision must be positive'
);

SELECT throws_ok(
    $query$INSERT INTO public.playlists (owner_id, title, visibility, description) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), 'Good', 'PRIVATE', repeat('x', 2001))$query$,
    '23514', NULL::text,
    'Playlist description max 2000 chars'
);

SELECT throws_ok(
    $query$INSERT INTO public.playlists (owner_id, title, visibility) VALUES (gen_random_uuid(), 'Good', 'PRIVATE')$query$,
    '23503', NULL::text,
    'Playlist requires existing profile owner'
);

SELECT throws_ok(
    $query$INSERT INTO public.music_pins (owner_id, title, geom, visibility) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), ' ', extensions.ST_GeogFromText('SRID=4326;POINT(44 56)'), 'PRIVATE')$query$,
    '23514', NULL::text,
    'Music PIN rejects empty title'
);

SELECT throws_ok(
    $query$INSERT INTO public.music_pins (owner_id, title, geom, visibility) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), 'Valid', extensions.ST_GeogFromText('SRID=4326;POINT(44 56)'), 'HIDDEN')$query$,
    '23514', NULL::text,
    'Music PIN rejects unknown visibility'
);

SELECT throws_ok(
    $query$INSERT INTO public.travel_events (owner_id, title, event_date, visibility) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), 'Good', CURRENT_DATE, 'PRIVATE')$query$,
    '23514', NULL::text,
    'Diary requires location, geometry or PIN'
);

SELECT throws_ok(
    $query$INSERT INTO public.music_journeys (owner_id, title, visibility, revision) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), 'Good', 'PRIVATE', 0)$query$,
    '23514', NULL::text,
    'Journey revision must be positive'
);

SELECT throws_ok(
    $query$INSERT INTO public.share_tokens (owner_id, token_hash) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), 'hash-without-target')$query$,
    '23514', NULL::text,
    'Share token requires exactly one target'
);

SELECT throws_ok(
    $query$INSERT INTO public.share_tokens (owner_id, token_hash, playlist_id) VALUES ((SELECT owner_id FROM pg_temp.ss_integrity_ctx), ' ', (SELECT playlist_id FROM pg_temp.ss_integrity_ctx))$query$,
    '23514', NULL::text,
    'Share token rejects whitespace-only hash'
);

SELECT throws_ok(
    $query$UPDATE public.profiles SET username = (SELECT username FROM public.profiles WHERE id = (SELECT owner_id FROM pg_temp.ss_integrity_ctx)) WHERE id = (SELECT viewer_id FROM pg_temp.ss_integrity_ctx)$query$,
    '23505', NULL::text,
    'Profile username is unique case-insensitively'
);

SELECT * FROM finish();
ROLLBACK;
