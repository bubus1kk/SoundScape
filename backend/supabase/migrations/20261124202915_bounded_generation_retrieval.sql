CREATE INDEX IF NOT EXISTS generation_track_title_idx ON public.tracks (lower(title));
CREATE INDEX IF NOT EXISTS generation_artist_name_idx ON public.artists (lower(name));

CREATE FUNCTION public.edge_generation_track_ids(
  p_genres text[], p_moods text[], p_excluded text[], p_genre_ids integer[], p_mood_ids integer[],
  p_kind text, p_target text, p_context_titles text[], p_pinned uuid[], p_limit integer,
  p_only_in_app boolean DEFAULT false
)
RETURNS TABLE(id uuid)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
  WITH evidence AS (
    SELECT tg.track_id, 80 AS score
    FROM public.track_genres tg JOIN public.genres g ON g.id=tg.genre_id
    WHERE lower(g.name)=ANY(p_genres)
    UNION ALL
    SELECT tm.track_id, 60
    FROM public.track_moods tm JOIN public.moods m ON m.id=tm.mood_id
    WHERE lower(m.name)=ANY(p_moods)
    UNION ALL
    SELECT pt.track_id, 12
    FROM public.playlists p JOIN public.playlist_tracks pt ON pt.playlist_id=p.id
    WHERE p.is_editorial AND p.visibility='PUBLIC' AND p.published_at IS NOT NULL
      AND lower(btrim(split_part(p.title,':',1)))=ANY(p_context_titles)
    UNION ALL
    SELECT pinned.id, 20 FROM unnest(p_pinned) AS pinned(id)
    UNION ALL
    SELECT t.id, 1000 FROM public.tracks t WHERE p_kind='TRACK' AND lower(t.title)=p_target
    UNION ALL
    SELECT ta.track_id, 1000 FROM public.track_artists ta JOIN public.artists a ON a.id=ta.artist_id
    WHERE p_kind='ARTIST' AND lower(a.name)=p_target
    UNION ALL
    SELECT tg.track_id, 1 FROM public.track_genres tg
    WHERE cardinality(p_genres)=0 AND cardinality(p_moods)=0 AND tg.genre_id=ANY(p_genre_ids)
    UNION ALL
    SELECT tm.track_id, 1 FROM public.track_moods tm
    WHERE cardinality(p_genres)=0 AND cardinality(p_moods)=0 AND tm.mood_id=ANY(p_mood_ids)
  )
  SELECT e.track_id FROM evidence e
  WHERE (p_kind NOT IN ('TRACK','ARTIST') OR e.score=1000)
    AND (cardinality(p_genre_ids)=0 OR EXISTS (
      SELECT 1 FROM public.track_genres g WHERE g.track_id=e.track_id AND g.genre_id=ANY(p_genre_ids)))
    AND (cardinality(p_mood_ids)=0 OR EXISTS (
      SELECT 1 FROM public.track_moods m WHERE m.track_id=e.track_id AND m.mood_id=ANY(p_mood_ids)))
    AND NOT EXISTS (
      SELECT 1 FROM public.track_genres tg JOIN public.genres g ON g.id=tg.genre_id
      WHERE tg.track_id=e.track_id AND lower(g.name)=ANY(p_excluded))
    AND EXISTS (
      SELECT 1 FROM public.track_sources s WHERE s.track_id=e.track_id AND s.verified_at IS NOT NULL
        AND (NOT p_only_in_app OR (s.provider='AUDIUS' AND s.can_stream)))
  GROUP BY e.track_id
  ORDER BY sum(e.score) DESC, EXISTS (
    SELECT 1 FROM public.track_sources s
    WHERE s.track_id=e.track_id AND s.provider='AUDIUS' AND s.can_stream
      AND s.verified_at>statement_timestamp()-interval '1 hour'
  ) DESC,e.track_id
  LIMIT least(greatest(p_limit,1),240);
$$;

REVOKE ALL ON FUNCTION public.edge_generation_track_ids(
  text[],text[],text[],integer[],integer[],text,text,text[],uuid[],integer,boolean
) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_generation_track_ids(
  text[],text[],text[],integer[],integer[],text,text,text[],uuid[],integer,boolean
) TO service_role;
