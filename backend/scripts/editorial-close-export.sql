\set ON_ERROR_STOP on
BEGIN;
DO $guard$
BEGIN
  IF NOT public.editorial_snapshot_target('ef013e53-d112-495b-b4f8-3cb97728e687')
     OR public.editorial_live_target('ef013e53-d112-495b-b4f8-3cb97728e687')
     OR (SELECT count(*) FROM internal.editorial_drafts) <> 2
     OR EXISTS (SELECT 1 FROM internal.editorial_drafts
                WHERE approved_revision IS NOT NULL OR published_revision IS NOT NULL) THEN
    RAISE EXCEPTION 'Expected the closed isolated pilot';
  END IF;
END
$guard$;

UPDATE internal.editorial_drafts d SET
  revision = d.revision + 1,
  cover_path = p.cover_path,
  track_ids = ARRAY[d.track_ids[2], d.track_ids[1]] || d.track_ids[3:13],
  description = CASE d.city_code
    WHEN 'tokyo' THEN replace(d.description,
      'Тёплый синт-поп расширяет электронную линию и связывает её с поколением YMO.',
      'City pop и future funk поднимают темп перед переходом к ночной электронике и фортепиано.')
    WHEN 'london' THEN replace(d.description,
      ' Гостевые Venna и Charlie Stacey сохранены в точном названии.', '')
  END
FROM public.playlists p
WHERE d.playlist_id = p.id AND d.revision = 2
  AND (d.city_code, p.id) IN (
    ('tokyo', '437a00a1-5fb8-5784-aa46-138823a50937'::uuid),
    ('london', '2ba4fc9e-2ba4-5117-b3c7-89f4b3724477'::uuid));

WITH drafts AS (
  SELECT city_code, place_id, playlist_id, revision, title, description, cover_path, genre_ids, tag_ids, track_ids
  FROM internal.editorial_drafts
), pl AS (
  SELECT p.* FROM public.playlists p JOIN drafts d ON d.playlist_id=p.id
), track_ids AS (
  SELECT unnest(track_ids) AS id FROM drafts UNION
  SELECT t.track_id FROM public.playlist_tracks t JOIN pl ON pl.id=t.playlist_id
), ta AS (
  SELECT t.* FROM public.track_artists t JOIN track_ids i ON i.id=t.track_id
), tg AS (
  SELECT t.* FROM public.track_genres t JOIN track_ids i ON i.id=t.track_id
), tm AS (
  SELECT t.* FROM public.track_moods t JOIN track_ids i ON i.id=t.track_id
), pg AS (
  SELECT t.* FROM public.playlist_genres t JOIN pl ON pl.id=t.playlist_id
), pt AS (
  SELECT t.* FROM public.playlist_tags t JOIN pl ON pl.id=t.playlist_id
)
SELECT jsonb_build_object(
 'drafts', (SELECT jsonb_agg(to_jsonb(d) ORDER BY city_code) FROM drafts d),
 'playlists', (SELECT jsonb_agg(to_jsonb(p)-'owner_id' ORDER BY id) FROM pl p),
 'places', (SELECT jsonb_agg(p ORDER BY p.id) FROM public.places p JOIN drafts d ON d.place_id=p.id),
 'tracks', (SELECT jsonb_agg(t ORDER BY t.id) FROM public.tracks t JOIN track_ids i ON i.id=t.id),
 'artists', (SELECT jsonb_agg(a ORDER BY id) FROM public.artists a WHERE a.id IN (SELECT artist_id FROM ta)),
 'audius_artist_identity', (SELECT coalesce(jsonb_agg(a ORDER BY provider_artist_id),'[]')
   FROM internal.audius_artist_identity a WHERE a.artist_id IN (SELECT artist_id FROM ta)),
 'genres', (SELECT jsonb_agg(g ORDER BY id) FROM public.genres g WHERE g.id IN (SELECT genre_id FROM tg UNION SELECT genre_id FROM pg)),
 'moods', (SELECT jsonb_agg(m ORDER BY id) FROM public.moods m WHERE m.id IN (SELECT mood_id FROM tm)),
 'tags', (SELECT jsonb_agg(t ORDER BY id) FROM public.tags t WHERE t.id IN (SELECT tag_id FROM pt)),
 'track_sources', (SELECT jsonb_agg(t ORDER BY t.track_id,t.provider,t.provider_track_id) FROM public.track_sources t JOIN track_ids i ON i.id=t.track_id),
 'track_artists', (SELECT jsonb_agg(ta ORDER BY track_id,credit_order) FROM ta),
 'track_genres', (SELECT coalesce(jsonb_agg(tg ORDER BY track_id,genre_id),'[]') FROM tg),
 'track_moods', (SELECT coalesce(jsonb_agg(tm ORDER BY track_id,mood_id),'[]') FROM tm),
 'playlist_tracks', (SELECT jsonb_agg(t ORDER BY t.playlist_id,t.position) FROM public.playlist_tracks t JOIN pl p ON p.id=t.playlist_id),
 'playlist_genres', (SELECT coalesce(jsonb_agg(pg ORDER BY playlist_id,genre_id),'[]') FROM pg),
 'playlist_tags', (SELECT coalesce(jsonb_agg(pt ORDER BY playlist_id,tag_id),'[]') FROM pt));
COMMIT;
