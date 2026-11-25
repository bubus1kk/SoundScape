-- The snapshot owns ordering for three minutes. Individual source verdicts
-- are read live, so expiring a stream must not remove its position.
CREATE FUNCTION public.edge_catalog_search_slots(
  p_hash text, p_offset integer, p_limit integer, p_refresh_from integer,
  p_probe_limit integer, p_items jsonb
)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path = '' AS $$
DECLARE
  snapshot jsonb;
  expiry timestamptz;
BEGIN
  IF p_hash IS NULL OR p_hash !~ '^[a-f0-9]{64}$'
    OR p_offset IS NULL OR p_offset NOT BETWEEN 0 AND 1000
    OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 1051
    OR p_refresh_from IS NULL OR p_refresh_from NOT BETWEEN p_offset AND p_offset + p_limit - 1
    OR p_probe_limit IS NULL OR p_probe_limit NOT BETWEEN 0 AND 6 THEN
    RAISE EXCEPTION 'INVALID_CATALOG_PAGE' USING ERRCODE = '22023';
  END IF;

  SELECT s.items,s.expires_at INTO snapshot,expiry FROM internal.catalog_search_snapshots s
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
    INSERT INTO internal.catalog_search_snapshots(query_hash,items,expires_at)
    VALUES (p_hash,p_items,now()+interval '3 minutes')
    ON CONFLICT (query_hash) DO UPDATE SET items=excluded.items,expires_at=excluded.expires_at
      WHERE internal.catalog_search_snapshots.expires_at<=now()
    RETURNING items,expires_at INTO snapshot,expiry;
    IF snapshot IS NULL THEN
      SELECT s.items,s.expires_at INTO snapshot,expiry FROM internal.catalog_search_snapshots s
      WHERE s.query_hash=p_hash;
    END IF;
  END IF;

  IF snapshot IS NULL THEN RETURN NULL; END IF;
  RETURN (
    WITH slots AS MATERIALIZED (
      SELECT entry.position,entry.value,source.provider_track_id,
        entry.value->>'availability'='IN_APP' AND
          NOT coalesce(source.verified_at>statement_timestamp()-interval '1 hour',false) AS unverified
      FROM jsonb_array_elements(snapshot) WITH ORDINALITY AS entry(value,position)
      LEFT JOIN LATERAL (
        SELECT s.provider_track_id,s.verified_at FROM public.track_sources s
        WHERE entry.value->>'availability'='IN_APP' AND s.track_id=(entry.value->>'id')::uuid
          AND s.provider='AUDIUS' AND s.can_stream AND s.verified_at IS NOT NULL
        ORDER BY s.verified_at DESC,s.provider_track_id LIMIT 1
      ) source ON true
      WHERE entry.position>p_offset AND entry.position<=p_offset+p_limit
    ), stale AS (
      SELECT position,value->>'id' AS track_id,provider_track_id FROM slots
      WHERE position>p_refresh_from AND unverified AND provider_track_id IS NOT NULL
      ORDER BY position LIMIT p_probe_limit
    )
    SELECT jsonb_build_object(
      'expiresAt',expiry,
      'slots',coalesce((SELECT jsonb_agg(CASE WHEN unverified THEN NULL ELSE value END ORDER BY position) FROM slots),'[]'),
      'stale',coalesce((SELECT jsonb_agg(to_jsonb(stale)-'position' ORDER BY position) FROM stale),'[]')
    )
  );
END;
$$;

REVOKE ALL ON FUNCTION public.edge_catalog_search_slots(text,integer,integer,integer,integer,jsonb)
  FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.edge_catalog_search_slots(text,integer,integer,integer,integer,jsonb)
  TO service_role;
