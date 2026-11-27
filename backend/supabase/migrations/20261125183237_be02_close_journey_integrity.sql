-- Keep surviving stops in order and compute route readiness from the caller's visible stops.
CREATE FUNCTION internal.journey_stop_state(p_journey uuid, p_public_only boolean)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = '' AS $$
  WITH visible AS (
    SELECT CASE WHEN s.place_id IS NOT NULL THEN 'PLACE' ELSE 'PIN' END AS kind,
      COALESCE(s.place_id, s.pin_id) AS id,
      row_number() OVER (ORDER BY s.position) AS position,
      (s.place_id IS NOT NULL OR internal.public_pin(s.pin_id)) AS can_generate
    FROM public.journey_places s
    WHERE s.journey_id = p_journey
      AND (s.pin_id IS NULL OR CASE WHEN p_public_only THEN internal.public_pin(s.pin_id)
                                   ELSE internal.can_read_pin(s.pin_id) END)
      AND (s.place_id IS NULL OR EXISTS (
        SELECT 1 FROM public.places p WHERE p.id = s.place_id AND p.is_published
      ))
  )
  SELECT jsonb_build_object(
    'stops', COALESCE(jsonb_agg(jsonb_build_object('kind',kind,'id',id,'position',position)
      ORDER BY position), '[]'::jsonb),
    'routeStatus', CASE WHEN count(*) >= 2 THEN 'READY' ELSE 'NEEDS_EDIT' END,
    'canGenerate', count(*) >= 2 AND COALESCE(bool_and(can_generate), false)
  ) FROM visible;
$$;
REVOKE ALL ON FUNCTION internal.journey_stop_state(uuid, boolean) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION internal.journey_detail(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT jsonb_build_object('id', j.id, 'ownerId', j.owner_id, 'title', j.title,
    'playlistId', CASE WHEN internal.can_read_playlist(j.playlist_id) THEN j.playlist_id END,
    'visibility', j.visibility, 'revision', j.revision)
    || internal.journey_stop_state(j.id, false)
    || internal.journey_durations(CASE WHEN internal.can_read_playlist(j.playlist_id) THEN j.playlist_id END)
  FROM public.music_journeys j WHERE j.id = p_id;
$$;

CREATE OR REPLACE FUNCTION internal.delete_music_pin(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_expected integer;v_owner uuid;v_revision integer;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_id:=($1->>'id')::uuid;v_expected:=($1->>'expectedRevision')::integer;
 IF v_id IS NULL OR v_expected IS NULL OR v_expected<1 THEN PERFORM internal.fail('PT422','INVALID_DELETE_REQUEST'); END IF;
 SELECT p.owner_id,p.revision INTO v_owner,v_revision FROM public.music_pins p WHERE p.id=v_id FOR UPDATE;
 IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','PIN_NOT_FOUND'); END IF;
 IF v_revision<>v_expected THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
 DELETE FROM public.music_pins WHERE id=v_id;
 RETURN jsonb_build_object('success',true,'id',v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.get_music_journey(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_id uuid; v_detail jsonb;
BEGIN
 PERFORM internal.assert_object($1);v_id:=($1->>'id')::uuid;
 IF v_id IS NULL OR NOT internal.can_read_journey(v_id) THEN PERFORM internal.fail('PT404','JOURNEY_NOT_FOUND'); END IF;
 v_detail := internal.journey_detail(v_id);
 IF v_detail->>'routeStatus' = 'NEEDS_EDIT' AND
    (v_detail->>'ownerId')::uuid IS DISTINCT FROM auth.uid() THEN
   PERFORM internal.fail('PT404','JOURNEY_NOT_FOUND');
 END IF;
 RETURN v_detail;
END;$$;

CREATE OR REPLACE FUNCTION public.list_music_journeys(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_scope text;v_uid uuid;v_limit integer;v_offset integer;v_total bigint;v_items jsonb;
BEGIN
 PERFORM internal.assert_object($1);
 v_scope:=COALESCE($1->>'scope','PUBLIC');
 v_limit:=COALESCE(($1->>'limit')::integer,20);v_offset:=COALESCE(($1->>'offset')::integer,0);
 PERFORM internal.assert_pagination(v_limit,v_offset);
 IF v_scope NOT IN ('PUBLIC','MINE') THEN PERFORM internal.fail('PT422','INVALID_SCOPE'); END IF;
 IF v_scope='MINE' THEN v_uid:=internal.require_user(); END IF;
 SELECT count(*) INTO v_total FROM public.music_journeys j
 WHERE (v_scope='MINE' AND j.owner_id=v_uid) OR (v_scope='PUBLIC' AND j.visibility='PUBLIC' AND internal.journey_stop_state(j.id, true)->>'routeStatus' = 'READY');
 SELECT COALESCE(jsonb_agg(internal.journey_detail(x.id) ORDER BY x.updated_at DESC,x.id),'[]'::jsonb)
 INTO v_items FROM (
   SELECT j.id,j.updated_at FROM public.music_journeys j
   WHERE (v_scope='MINE' AND j.owner_id=v_uid) OR (v_scope='PUBLIC' AND j.visibility='PUBLIC' AND internal.journey_stop_state(j.id, true)->>'routeStatus' = 'READY')
   ORDER BY j.updated_at DESC,j.id LIMIT v_limit OFFSET v_offset
 ) x;
 RETURN jsonb_build_object('items',v_items,'limit',v_limit,'offset',v_offset,
   'hasMore',(v_total>v_offset::bigint+v_limit));
END;$$;

-- Reuse existing visibility, expiry, ownership and serializer rules before checking the shared route.
ALTER FUNCTION public.edge_open_share(text) SET SCHEMA internal;
ALTER FUNCTION public.edge_open_public(text, uuid) SET SCHEMA internal;
REVOKE ALL ON FUNCTION internal.edge_open_share(text), internal.edge_open_public(text, uuid)
  FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION internal.shared_journey_readiness(shared jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SET search_path = '' AS $$
DECLARE state jsonb;
BEGIN
  IF shared->>'objectType' IS DISTINCT FROM 'JOURNEY' THEN RETURN shared; END IF;
  state := internal.journey_stop_state((shared->>'id')::uuid, true);
  IF state->>'routeStatus' <> 'READY' THEN RETURN NULL; END IF;
  RETURN jsonb_set(shared, '{payload}', (shared->'payload') || state);
END;
$$;
REVOKE ALL ON FUNCTION internal.shared_journey_readiness(jsonb) FROM PUBLIC, anon, authenticated;

CREATE FUNCTION public.edge_open_share(p_hash text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.shared_journey_readiness(internal.edge_open_share(p_hash));
$$;

CREATE FUNCTION public.edge_open_public(p_kind text, p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT internal.shared_journey_readiness(internal.edge_open_public(p_kind, p_id));
$$;
REVOKE ALL ON FUNCTION public.edge_open_share(text), public.edge_open_public(text, uuid)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_open_share(text), public.edge_open_public(text, uuid) TO service_role;

CREATE OR REPLACE FUNCTION public.list_public_profile_items(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  target uuid := ($1->>'profileId')::uuid;
  kind text := $1->>'kind';
  page_limit integer := COALESCE(($1->>'limit')::integer, 20);
  page_offset integer := COALESCE(($1->>'offset')::integer, 0);
  items jsonb;
  has_more boolean;
BEGIN
  PERFORM public.get_public_profile(jsonb_build_object('profileId', target));
  PERFORM internal.assert_pagination(page_limit, page_offset);
  IF kind IS NULL OR kind NOT IN ('PLAYLIST', 'MUSIC_PIN', 'TRAVEL_EVENT', 'JOURNEY') THEN
    PERFORM internal.fail('PT422', 'INVALID_PROFILE_ITEM_KIND');
  END IF;
  -- Public mode uses the same safe summary for every caller, including the owner.
  WITH candidates AS (
    SELECT p.id, p.title, p.created_at FROM public.playlists p
      WHERE kind = 'PLAYLIST' AND p.owner_id = target AND p.visibility = 'PUBLIC'
        AND (NOT p.is_editorial OR p.published_at IS NOT NULL)
    UNION ALL
    SELECT p.id, p.title, p.created_at FROM public.music_pins p
      WHERE kind = 'MUSIC_PIN' AND p.owner_id = target AND p.visibility = 'PUBLIC'
    UNION ALL
    SELECT e.id, e.title, e.created_at FROM public.travel_events e
      WHERE kind = 'TRAVEL_EVENT' AND e.owner_id = target AND e.visibility = 'PUBLIC'
    UNION ALL
    SELECT j.id, j.title, j.created_at FROM public.music_journeys j
      WHERE kind = 'JOURNEY' AND j.owner_id = target AND j.visibility = 'PUBLIC'
        AND internal.journey_stop_state(j.id, true)->>'routeStatus' = 'READY'
  ), page AS (
    SELECT *, row_number() OVER (ORDER BY created_at DESC, id) AS position
    FROM (SELECT * FROM candidates ORDER BY created_at DESC, id LIMIT page_limit + 1 OFFSET page_offset) selected
  )
  SELECT COALESCE(jsonb_agg(jsonb_build_object('id', id, 'title', title, 'kind', kind)
        ORDER BY position) FILTER (WHERE position <= page_limit), '[]'::jsonb),
      count(*) > page_limit INTO items, has_more FROM page;
  RETURN jsonb_build_object('items', items, 'limit', page_limit, 'offset', page_offset, 'hasMore', has_more);
END;
$$;

NOTIFY pgrst, 'reload schema';
