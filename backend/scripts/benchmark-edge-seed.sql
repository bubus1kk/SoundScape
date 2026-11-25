-- Use only with a disposable local database. Run with -v scale=300 (or 1000, 5000, 10000).
\set ON_ERROR_STOP on
BEGIN;
DELETE FROM public.tracks WHERE title LIKE 'BE01-FINAL benchmark %';
INSERT INTO public.genres(name) VALUES('Ambient') ON CONFLICT DO NOTHING;
INSERT INTO public.moods(name) VALUES('Peaceful') ON CONFLICT DO NOTHING;
INSERT INTO public.tracks(id,title,duration_ms)
SELECT md5('be01-final-'||i)::uuid,
  'BE01-FINAL benchmark '||CASE WHEN i%5=0 THEN 'Ambient ' ELSE 'Other ' END||i,90000
FROM generate_series(1,:scale) i;
INSERT INTO public.track_genres(track_id,genre_id)
SELECT md5('be01-final-'||i)::uuid,g.id FROM generate_series(1,:scale) i
JOIN public.genres g ON lower(g.name)='ambient' WHERE i%5=0;
INSERT INTO public.track_moods(track_id,mood_id)
SELECT md5('be01-final-'||i)::uuid,m.id FROM generate_series(1,:scale) i
JOIN public.moods m ON lower(m.name)='peaceful' WHERE i%5=0;
INSERT INTO public.track_sources(track_id,provider,provider_track_id,external_url,can_stream,verified_at)
SELECT md5('be01-final-'||i)::uuid,'AUDIUS','Be01Final'||i,'https://audius.co/fixture/be01-'||i,true,now()
FROM generate_series(1,:scale) i;
ANALYZE public.tracks;
ANALYZE public.track_sources;
ANALYZE public.track_genres;
ANALYZE public.track_moods;
COMMIT;
