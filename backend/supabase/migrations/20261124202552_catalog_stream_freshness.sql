-- Preserve existing service-role grants and public response shapes.
CREATE OR REPLACE FUNCTION public.edge_search_catalog(
  p_query text, p_genres integer[], p_moods integer[], p_only_in_app boolean,
  p_live_ids uuid[], p_offset integer, p_limit integer
)
RETURNS TABLE(id uuid, title text, artist text, "durationMs" integer, availability text, provider text)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
  SELECT t.id, t.title, coalesce(credits.artist, 'Unknown artist'), t.duration_ms,
    CASE WHEN source.can_stream THEN 'IN_APP' ELSE 'EXTERNAL_ONLY' END, source.provider::text
  FROM public.tracks t
  LEFT JOIN LATERAL (
    SELECT string_agg(a.name, ', ' ORDER BY ta.credit_order) AS artist
    FROM public.track_artists ta JOIN public.artists a ON a.id = ta.artist_id
    WHERE ta.track_id = t.id
  ) credits ON true
  JOIN LATERAL (
    SELECT s.provider, (s.provider = 'AUDIUS' AND s.can_stream AND s.verified_at > statement_timestamp() - interval '1 hour') AS can_stream
    FROM public.track_sources s
    WHERE s.track_id = t.id AND s.verified_at IS NOT NULL AND (
      (s.provider = 'AUDIUS' AND s.can_stream AND s.verified_at > statement_timestamp() - interval '1 hour') OR (NOT p_only_in_app AND (s.provider <> 'AUDIUS' OR NOT s.can_stream) AND (
        (s.provider = 'AUDIUS' AND s.external_url ~ '^https://(www\.)?audius\.co/[A-Za-z0-9_\-/%?=.&]+$')
        OR (s.provider = 'APPLE_MUSIC' AND s.external_url ~ '^https://music\.apple\.com/[^[:space:]]+$')
        OR (s.provider = 'BANDCAMP' AND s.external_url ~ '^https://[a-z0-9-]+\.bandcamp\.com/[^[:space:]]+$')
      ))
    )
    ORDER BY can_stream DESC, s.provider, s.provider_track_id
    LIMIT 1
  ) source ON true
  WHERE (t.id = ANY(p_live_ids) OR strpos(lower(t.title), lower(p_query)) > 0
    OR strpos(lower(credits.artist), lower(p_query)) > 0)
    AND (t.id = ANY(p_live_ids) OR cardinality(p_genres) = 0 OR EXISTS (
      SELECT 1 FROM public.track_genres g WHERE g.track_id = t.id AND g.genre_id = ANY(p_genres)
    ))
    AND (t.id = ANY(p_live_ids) OR cardinality(p_moods) = 0 OR EXISTS (
      SELECT 1 FROM public.track_moods m WHERE m.track_id = t.id AND m.mood_id = ANY(p_moods)
    ))
  ORDER BY lower(t.title), t.id
  LIMIT least(greatest(p_limit, 1), 1051)
  OFFSET greatest(p_offset, 0);
$$;

CREATE OR REPLACE FUNCTION public.edge_catalog_search_page(
  p_hash text, p_offset integer, p_limit integer, p_items jsonb DEFAULT NULL
)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = '' AS $$
DECLARE
  snapshot jsonb;
  expiry timestamptz;
BEGIN
  IF p_hash IS NULL OR p_hash !~ '^[a-f0-9]{64}$'
    OR p_offset IS NULL OR p_offset NOT BETWEEN 0 AND 1000
    OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 1051 THEN
    RAISE EXCEPTION 'INVALID_CATALOG_PAGE' USING ERRCODE = '22023';
  END IF;

  SELECT s.items INTO snapshot FROM internal.catalog_search_snapshots s
  WHERE s.query_hash = p_hash AND s.expires_at > now();

  IF snapshot IS NULL AND p_items IS NOT NULL THEN
    IF jsonb_typeof(p_items) <> 'array' OR jsonb_array_length(p_items) > 1051 THEN
      RAISE EXCEPTION 'INVALID_CATALOG_SNAPSHOT' USING ERRCODE = '22023';
    END IF;
    DELETE FROM internal.catalog_search_snapshots s
    WHERE s.query_hash IN (
      SELECT expired.query_hash FROM internal.catalog_search_snapshots expired
      WHERE expired.expires_at <= now() AND expired.query_hash <> p_hash
      ORDER BY expired.expires_at LIMIT 50 FOR UPDATE SKIP LOCKED
    );
    SELECT least(now() + interval '3 minutes', coalesce(min(source.expires_at), 'infinity'::timestamptz))
      INTO expiry
    FROM jsonb_array_elements(p_items) AS item(value)
    LEFT JOIN LATERAL (
      SELECT coalesce(max(s.verified_at) + interval '1 hour', now()) AS expires_at
      FROM public.track_sources s
      WHERE s.track_id = (item.value->>'id')::uuid AND s.provider = 'AUDIUS' AND s.can_stream
    ) source ON true
    WHERE item.value->>'availability' = 'IN_APP';
    INSERT INTO internal.catalog_search_snapshots(query_hash, items, expires_at)
    VALUES (p_hash, p_items, expiry)
    ON CONFLICT (query_hash) DO UPDATE SET items = EXCLUDED.items, expires_at = EXCLUDED.expires_at
      WHERE internal.catalog_search_snapshots.expires_at <= now()
    RETURNING items INTO snapshot;
    IF snapshot IS NULL THEN
      SELECT s.items INTO snapshot FROM internal.catalog_search_snapshots s WHERE s.query_hash = p_hash;
    END IF;
  END IF;

  IF snapshot IS NULL THEN RETURN NULL; END IF;
  RETURN coalesce((
    SELECT jsonb_agg(entry.value ORDER BY entry.position)
    FROM jsonb_array_elements(snapshot) WITH ORDINALITY AS entry(value, position)
    WHERE entry.position > p_offset AND entry.position <= p_offset + p_limit
    AND (
      entry.value->>'availability' IS DISTINCT FROM 'IN_APP' OR EXISTS (
        SELECT 1 FROM public.track_sources s
        WHERE s.track_id = (entry.value->>'id')::uuid AND s.provider = 'AUDIUS' AND s.can_stream
          AND s.verified_at > statement_timestamp() - interval '1 hour'
      )
    )
  ), '[]'::jsonb);
END;
$$;

CREATE FUNCTION public.edge_expired_audius_sources(
  p_query text,p_genres integer[],p_moods integer[],p_limit integer
) RETURNS TABLE(track_id uuid,provider_track_id text)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
  SELECT DISTINCT ON (s.track_id) s.track_id,s.provider_track_id
  FROM public.track_sources s JOIN public.tracks t ON t.id=s.track_id
  WHERE s.provider='AUDIUS' AND s.can_stream AND s.verified_at IS NOT NULL
    AND s.verified_at<=statement_timestamp()-interval '1 hour'
    AND (strpos(lower(t.title),lower(p_query))>0 OR EXISTS (
      SELECT 1 FROM public.track_artists ta JOIN public.artists a ON a.id=ta.artist_id
      WHERE ta.track_id=t.id AND strpos(lower(a.name),lower(p_query))>0))
    AND (cardinality(p_genres)=0 OR EXISTS (
      SELECT 1 FROM public.track_genres g WHERE g.track_id=t.id AND g.genre_id=ANY(p_genres)))
    AND (cardinality(p_moods)=0 OR EXISTS (
      SELECT 1 FROM public.track_moods m WHERE m.track_id=t.id AND m.mood_id=ANY(p_moods)))
  ORDER BY s.track_id,s.verified_at DESC,s.provider_track_id
  LIMIT least(greatest(p_limit,0),6);
$$;
REVOKE ALL ON FUNCTION public.edge_expired_audius_sources(text,integer[],integer[],integer)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_expired_audius_sources(text,integer[],integer[],integer)
  TO service_role;

CREATE FUNCTION public.edge_fresh_audius_track_ids(p_ids uuid[])
RETURNS TABLE(track_id uuid)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
  SELECT DISTINCT s.track_id FROM public.track_sources s
  WHERE cardinality(p_ids)<=10000 AND s.track_id=ANY(p_ids)
    AND s.provider='AUDIUS' AND s.can_stream
    AND s.verified_at>statement_timestamp()-interval '1 hour';
$$;
REVOKE ALL ON FUNCTION public.edge_fresh_audius_track_ids(uuid[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_fresh_audius_track_ids(uuid[]) TO service_role;
