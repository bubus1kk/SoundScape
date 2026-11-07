-- SoundScape Practice 11. LOCAL database only. No cloud/production invocation.
-- The Supabase CLI pgTAP runner uses a transaction per test file.
-- All fixture data is rolled back; do not use --linked or --db-url.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path = public, extensions, pg_catalog;
SELECT plan(26);

SELECT has_table('public', 'roles', 'public.roles exists');

SELECT has_table('public', 'profiles', 'public.profiles exists');

SELECT has_table('public', 'places', 'public.places exists');

SELECT has_table('public', 'tracks', 'public.tracks exists');

SELECT has_table('public', 'track_sources', 'public.track_sources exists');

SELECT has_table('public', 'playlists', 'public.playlists exists');

SELECT has_table('public', 'music_pins', 'public.music_pins exists');

SELECT has_table('public', 'travel_events', 'public.travel_events exists');

SELECT has_table('public', 'music_journeys', 'public.music_journeys exists');

SELECT has_table('public', 'share_tokens', 'public.share_tokens exists');

SELECT has_table('internal', 'api_quota_counters', 'internal quota table exists');

SELECT is((SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r'), 29::bigint, '29 application tables expected');

SELECT is((SELECT count(*) FROM pg_catalog.pg_class WHERE relnamespace = 'public'::regnamespace AND relkind = 'r' AND relrowsecurity), 29::bigint, 'RLS enabled on all application tables');

SELECT ok((SELECT relrowsecurity FROM pg_catalog.pg_class WHERE oid='internal.api_quota_counters'::regclass), 'RLS enabled on internal quota table');

SELECT ok(has_table_privilege('anon', 'public.playlists', 'SELECT'), 'Guest can SELECT playlists subject to RLS');

SELECT ok(NOT has_table_privilege('anon', 'public.playlists', 'INSERT'), 'Guest cannot INSERT playlists directly');

SELECT ok(NOT has_table_privilege('authenticated', 'public.playlists', 'INSERT'), 'Mutations go through RPC, not direct INSERT');

SELECT ok(NOT has_table_privilege('anon', 'public.share_tokens', 'SELECT'), 'Guest cannot read raw share tokens');

SELECT ok(NOT has_table_privilege('authenticated', 'public.share_tokens', 'SELECT'), 'Authenticated users cannot read raw share tokens');

SELECT ok(NOT has_table_privilege('anon', 'internal.api_quota_counters', 'SELECT'), 'Guest cannot read quota internals');

SELECT ok(NOT has_column_privilege('authenticated', 'public.profiles', 'role_code', 'UPDATE'), 'Authenticated user cannot escalate role_code directly');

SELECT ok(to_regprocedure('internal.can_read_playlist(uuid)') IS NOT NULL, 'Playlist RLS helper exists');

SELECT ok(to_regprocedure('internal.can_read_pin(uuid)') IS NOT NULL, 'PIN RLS helper exists');

SELECT ok(to_regprocedure('internal.can_read_event(uuid)') IS NOT NULL, 'Diary RLS helper exists');

SELECT ok(to_regprocedure('internal.can_read_journey(uuid)') IS NOT NULL, 'Journey RLS helper exists');

SELECT is((SELECT count(*) FROM (VALUES
    ('get_visible_places'),
    ('get_nearby_places'),
    ('list_playlists'),
    ('get_playlist'),
    ('save_playlist'),
    ('delete_playlist'),
    ('save_music_pin'),
    ('save_travel_event'),
    ('list_travel_events'),
    ('save_music_journey'),
    ('get_music_pin'),
    ('get_travel_event'),
    ('get_music_journey'),
    ('list_music_journeys'),
    ('delete_music_pin'),
    ('delete_travel_event'),
    ('delete_music_journey')
) rpc(name) WHERE to_regprocedure(format('public.%I(jsonb)', rpc.name)) IS NOT NULL), 17::bigint, 'All 17 RPC functions accept one jsonb argument');

SELECT * FROM finish();
ROLLBACK;
