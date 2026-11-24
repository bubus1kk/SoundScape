-- Reuse the existing provider-ID lock and stable track UUID for both live search and bulk import.
CREATE FUNCTION public.edge_import_audius_batch(p_items jsonb)
RETURNS TABLE(id uuid, provider_track_id text)
LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = '' AS $$
DECLARE
  item record;
  track_uuid uuid;
  facet_id smallint;
BEGIN
  IF jsonb_typeof(p_items) IS DISTINCT FROM 'array' THEN
    RAISE EXCEPTION 'INVALID_AUDIUS_BATCH' USING ERRCODE = '22023';
  END IF;
  IF jsonb_array_length(p_items) NOT BETWEEN 1 AND 25 THEN
    RAISE EXCEPTION 'INVALID_AUDIUS_BATCH' USING ERRCODE = '22023';
  END IF;

  -- Lock candidates in a consistent order across overlapping batches.
  FOR item IN SELECT * FROM jsonb_to_recordset(p_items) AS x(
    id text, title text, artist text, "durationMs" integer, permalink text,
    genre text, mood text, "canStream" boolean
  ) ORDER BY x.id LOOP
    IF item."canStream" IS NULL OR item.id IS NULL OR item.id !~ '^[A-Za-z0-9-]{1,120}$'
      OR length(item.genre) > 80 OR length(item.mood) > 80 THEN
      RAISE EXCEPTION 'INVALID_AUDIUS_CANDIDATE' USING ERRCODE = '22023';
    END IF;
    track_uuid := public.edge_ingest_audius(
      item.id, item.title, item.artist, item."durationMs", item.permalink
    );
    PERFORM public.edge_mark_streamable(track_uuid, item.id, item."canStream");
    id := track_uuid;
    provider_track_id := item.id;
    RETURN NEXT;

  END LOOP;

  -- Taxonomy writes are serialized independently of provider track IDs.
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('AUDIUS_FACETS'), 0);

  -- Sorted track locks are acquired before touching shared facet rows.
  FOR item IN SELECT t.track_id AS track_uuid, x.genre, x.mood
    FROM jsonb_to_recordset(p_items) AS x(id text, genre text, mood text)
    JOIN public.track_sources t ON t.provider = 'AUDIUS' AND t.provider_track_id = x.id
  LOOP
    IF length(btrim(item.genre)) > 0 THEN
      SELECT g.id INTO facet_id FROM public.genres g WHERE lower(g.name) = lower(btrim(item.genre));
      IF facet_id IS NULL THEN
        INSERT INTO public.genres(name) VALUES (btrim(item.genre)) ON CONFLICT (lower(name)) DO NOTHING;
        SELECT g.id INTO facet_id FROM public.genres g WHERE lower(g.name) = lower(btrim(item.genre));
      END IF;
      INSERT INTO public.track_genres(track_id, genre_id)
        VALUES (item.track_uuid, facet_id) ON CONFLICT DO NOTHING;
    END IF;
    IF length(btrim(item.mood)) > 0 THEN
      SELECT m.id INTO facet_id FROM public.moods m WHERE lower(m.name) = lower(btrim(item.mood));
      IF facet_id IS NULL THEN
        INSERT INTO public.moods(name) VALUES (btrim(item.mood)) ON CONFLICT (lower(name)) DO NOTHING;
        SELECT m.id INTO facet_id FROM public.moods m WHERE lower(m.name) = lower(btrim(item.mood));
      END IF;
      INSERT INTO public.track_moods(track_id, mood_id)
        VALUES (item.track_uuid, facet_id) ON CONFLICT DO NOTHING;
    END IF;
  END LOOP;
END;
$$;

-- NULL represents an explicitly withdrawn source. Provider outages never call this RPC.
CREATE OR REPLACE FUNCTION public.edge_mark_streamable(p_track uuid, p_provider_id text, p_allowed boolean)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  UPDATE public.track_sources
  SET can_stream = coalesce(p_allowed, false), can_cache = false, can_download = false,
      verified_at = CASE WHEN p_allowed IS NOT NULL THEN now() END
  WHERE track_id = p_track AND provider = 'AUDIUS' AND provider_track_id = p_provider_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'UNKNOWN_SOURCE' USING ERRCODE = '22023'; END IF;
END;
$$;

CREATE FUNCTION public.edge_search_catalog(
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
    SELECT s.provider, (s.provider = 'AUDIUS' AND s.can_stream) AS can_stream
    FROM public.track_sources s
    WHERE s.track_id = t.id AND s.verified_at IS NOT NULL AND (
      (s.provider = 'AUDIUS' AND s.can_stream) OR (NOT p_only_in_app AND (
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

-- A short-lived, public-music-only result set keeps offset pages stable across Edge workers.
CREATE TABLE internal.catalog_search_snapshots (
  query_hash text PRIMARY KEY,
  items jsonb NOT NULL,
  expires_at timestamptz NOT NULL
);
CREATE INDEX catalog_search_snapshots_expiry_idx ON internal.catalog_search_snapshots(expires_at);

CREATE FUNCTION public.edge_catalog_search_page(
  p_hash text, p_offset integer, p_limit integer, p_items jsonb DEFAULT NULL
)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = '' AS $$
DECLARE
  snapshot jsonb;
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
    INSERT INTO internal.catalog_search_snapshots(query_hash, items, expires_at)
    VALUES (p_hash, p_items, now() + interval '3 minutes')
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
  ), '[]'::jsonb);
END;
$$;

GRANT USAGE ON SCHEMA internal TO service_role;
GRANT SELECT, INSERT, UPDATE, DELETE ON internal.catalog_search_snapshots TO service_role;

REVOKE ALL ON FUNCTION public.edge_import_audius_batch(jsonb),
  public.edge_search_catalog(text, integer[], integer[], boolean, uuid[], integer, integer),
  public.edge_catalog_search_page(text, integer, integer, jsonb)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_import_audius_batch(jsonb),
  public.edge_search_catalog(text, integer[], integer[], boolean, uuid[], integer, integer),
  public.edge_catalog_search_page(text, integer, integer, jsonb)
  TO service_role;
