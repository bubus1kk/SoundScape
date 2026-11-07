-- SoundScape / block B: PostgreSQL RPC + guarded object CRUD.
-- Compatible with initial_schema + auth_rls_storage migrations discussed 2026-11-06.
-- COPY INTO A NEW CLI-GENERATED MIGRATION (supabase migration new rpc_crud).
-- Never deploy to cloud before local tests.
-- Design: one UNNAMED jsonb arg so the camelCase OpenAPI body can be sent as-is.
-- No service_role secret in clients. All SECURITY DEFINER entrypoints verify JWT/ownership.

-- ===========================================================
-- 0. Infrastructure helpers in NON-EXPOSED schema
-- ===========================================================
CREATE OR REPLACE FUNCTION internal.fail(p_code text, p_message text)
RETURNS void LANGUAGE plpgsql VOLATILE SET search_path = '' AS $$
BEGIN
  RAISE EXCEPTION USING ERRCODE = p_code, MESSAGE = p_message;
END;
$$;

CREATE OR REPLACE FUNCTION internal.require_user()
RETURNS uuid LANGUAGE plpgsql STABLE SET search_path = '' AS $$
DECLARE v_uid uuid;
BEGIN
  v_uid := (SELECT auth.uid());
  IF v_uid IS NULL THEN
    PERFORM internal.fail('PT401', 'AUTH_REQUIRED');
  END IF;
  RETURN v_uid;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_object(p_data jsonb)
RETURNS void LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
  IF p_data IS NULL OR jsonb_typeof(p_data) <> 'object' THEN
    PERFORM internal.fail('PT422', 'JSON_OBJECT_REQUIRED');
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_title(p_value text)
RETURNS void LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
  IF p_value IS NULL OR char_length(btrim(p_value)) NOT BETWEEN 1 AND 150 THEN
    PERFORM internal.fail('PT422', 'INVALID_TITLE');
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_visibility(p_value text)
RETURNS void LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
  IF p_value IS NULL OR p_value NOT IN ('PUBLIC','UNLISTED','PRIVATE') THEN
    PERFORM internal.fail('PT422', 'INVALID_VISIBILITY');
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_pagination(p_limit integer, p_offset integer)
RETURNS void LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR p_offset IS NULL OR p_offset < 0 THEN
    PERFORM internal.fail('PT422', 'INVALID_PAGINATION');
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_tracks(p_value jsonb, p_max integer, p_unique boolean)
RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_n integer; v_valid integer;
BEGIN
  IF jsonb_typeof(p_value) IS DISTINCT FROM 'array' THEN
    PERFORM internal.fail('PT422', 'TRACK_IDS_MUST_BE_ARRAY');
  END IF;
  v_n := jsonb_array_length(p_value);
  IF v_n NOT BETWEEN 1 AND p_max THEN
    PERFORM internal.fail('PT422', 'INVALID_TRACK_COUNT');
  END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_value) AS x(item) WHERE jsonb_typeof(x.item) <> 'string') THEN
    PERFORM internal.fail('PT422', 'INVALID_TRACK_ID');
  END IF;
  SELECT count(*) INTO v_valid
  FROM jsonb_array_elements_text(p_value) AS x(id)
  JOIN public.tracks t ON t.id = x.id::uuid;
  IF v_valid <> v_n THEN
    PERFORM internal.fail('PT422', 'UNKNOWN_TRACK');
  END IF;
  IF p_unique AND (SELECT count(DISTINCT id) FROM jsonb_array_elements_text(p_value) AS x(id)) <> v_n THEN
    PERFORM internal.fail('PT422', 'DUPLICATE_TRACK');
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_ids(p_value jsonb, p_table text, p_max integer)
RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_n integer; v_found integer;
BEGIN
  IF p_value IS NULL THEN RETURN; END IF;
  IF jsonb_typeof(p_value) <> 'array' THEN
    PERFORM internal.fail('PT422', 'IDS_MUST_BE_ARRAY');
  END IF;
  v_n := jsonb_array_length(p_value);
  IF v_n > p_max THEN PERFORM internal.fail('PT422', 'TOO_MANY_IDS'); END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_value) AS x(item) WHERE jsonb_typeof(x.item) <> 'number') THEN
    PERFORM internal.fail('PT422', 'INVALID_ID');
  END IF;
  IF (SELECT count(DISTINCT id) FROM jsonb_array_elements_text(p_value) AS x(id)) <> v_n THEN
    PERFORM internal.fail('PT422', 'DUPLICATE_ID');
  END IF;
  IF p_table = 'genres' THEN
    SELECT count(*) INTO v_found FROM jsonb_array_elements_text(p_value) x(id)
    JOIN public.genres g ON g.id = x.id::smallint;
  ELSIF p_table = 'tags' THEN
    SELECT count(*) INTO v_found FROM jsonb_array_elements_text(p_value) x(id)
    JOIN public.tags t ON t.id = x.id::smallint;
  ELSE
    PERFORM internal.fail('PT500', 'INVALID_SERVER_TABLE');
  END IF;
  IF v_found <> v_n THEN PERFORM internal.fail('PT422', 'UNKNOWN_ID'); END IF;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_media(p_path text, p_owner uuid)
RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF p_path IS NULL THEN RETURN; END IF;
  IF p_path NOT LIKE ('users/' || p_owner::text || '/%')
      OR NOT EXISTS (
        SELECT 1 FROM storage.objects s
        WHERE s.bucket_id = 'soundscape-media' AND s.name = p_path
      ) THEN
    PERFORM internal.fail('PT422', 'INVALID_MEDIA_PATH');
  END IF;
END;
$$;

CREATE OR REPLACE FUNCTION internal.assert_media_array(p_paths jsonb, p_owner uuid, p_max integer)
RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_path text;
BEGIN
  IF p_paths IS NULL THEN RETURN; END IF;
  IF jsonb_typeof(p_paths) <> 'array' OR jsonb_array_length(p_paths) > p_max THEN
    PERFORM internal.fail('PT422', 'INVALID_PHOTO_COUNT');
  END IF;
  IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_paths) x(item) WHERE jsonb_typeof(x.item) <> 'string') THEN
    PERFORM internal.fail('PT422', 'INVALID_PHOTO_PATH');
  END IF;
  IF (SELECT count(DISTINCT path) FROM jsonb_array_elements_text(p_paths) x(path)) <> jsonb_array_length(p_paths) THEN
    PERFORM internal.fail('PT422', 'DUPLICATE_PHOTO_PATH');
  END IF;
  FOR v_path IN SELECT value FROM jsonb_array_elements_text(p_paths) LOOP
    PERFORM internal.assert_media(v_path, p_owner);
  END LOOP;
END;
$$;

-- NO client execution rights for internal helpers, despite USAGE on schema internal.
REVOKE ALL ON FUNCTION internal.fail(text,text), internal.require_user(),
 internal.assert_object(jsonb), internal.assert_title(text), internal.assert_visibility(text),
 internal.assert_pagination(integer,integer), internal.assert_tracks(jsonb,integer,boolean),
 internal.assert_ids(jsonb,text,integer), internal.assert_media(text,uuid),
 internal.assert_media_array(jsonb,uuid,integer)
FROM PUBLIC, anon, authenticated;

-- ===========================================================
-- 1. DTO serializers (server-only, no private source URLs)
-- ===========================================================
CREATE OR REPLACE FUNCTION internal.track_view(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT jsonb_build_object(
     'id', t.id,
     'title', t.title,
     'artist', COALESCE((SELECT string_agg(a.name, ', ' ORDER BY ta.credit_order)
                         FROM public.track_artists ta
                         JOIN public.artists a ON a.id = ta.artist_id
                         WHERE ta.track_id = t.id), 'Unknown artist'),
     'durationMs', t.duration_ms,
     'availability', CASE WHEN EXISTS (
         SELECT 1 FROM public.track_sources ts
         WHERE ts.track_id = t.id AND ts.can_stream AND ts.verified_at IS NOT NULL
       ) THEN 'IN_APP' ELSE 'EXTERNAL_ONLY' END,
     'provider', (SELECT ts.provider FROM public.track_sources ts
                   WHERE ts.track_id=t.id AND ts.verified_at IS NOT NULL
                   ORDER BY ts.can_stream DESC, ts.verified_at DESC LIMIT 1)
  )
  FROM public.tracks t WHERE t.id = p_id;
$$;

CREATE OR REPLACE FUNCTION internal.playlist_detail(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id', p.id, 'ownerId', p.owner_id, 'title', p.title,
   'description', p.description, 'visibility', p.visibility,
   'revision', p.revision, 'placeId', CASE WHEN p.place_id IS NULL OR p.owner_id=(SELECT auth.uid())
     OR EXISTS (SELECT 1 FROM public.places plc WHERE plc.id=p.place_id AND plc.is_published)
     THEN p.place_id ELSE NULL END,
   'coverPath', p.cover_path, 'isEditorial', p.is_editorial,
   'genreIds', COALESCE((SELECT jsonb_agg(pg.genre_id ORDER BY pg.genre_id)
                        FROM public.playlist_genres pg WHERE pg.playlist_id=p.id),'[]'::jsonb),
   'tagIds', COALESCE((SELECT jsonb_agg(pt.tag_id ORDER BY pt.tag_id)
                      FROM public.playlist_tags pt WHERE pt.playlist_id=p.id),'[]'::jsonb),
   'items', COALESCE((SELECT jsonb_agg(
          jsonb_build_object('position',pt.position,'track', internal.track_view(pt.track_id))
          ORDER BY pt.position) FROM public.playlist_tracks pt
          WHERE pt.playlist_id=p.id),'[]'::jsonb)
 ) FROM public.playlists p WHERE p.id=p_id;
$$;

CREATE OR REPLACE FUNCTION internal.playlist_summary(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id', p.id, 'ownerId', p.owner_id, 'title', p.title,
   'description', p.description, 'visibility', p.visibility,
   'revision', p.revision, 'placeId', CASE WHEN p.place_id IS NULL OR p.owner_id=(SELECT auth.uid())
     OR EXISTS (SELECT 1 FROM public.places plc WHERE plc.id=p.place_id AND plc.is_published)
     THEN p.place_id ELSE NULL END,
   'coverPath', p.cover_path, 'isEditorial', p.is_editorial,
   'genreIds', COALESCE((SELECT jsonb_agg(g.genre_id ORDER BY g.genre_id)
                        FROM public.playlist_genres g WHERE g.playlist_id=p.id),'[]'::jsonb),
   'tagIds', COALESCE((SELECT jsonb_agg(t.tag_id ORDER BY t.tag_id)
                      FROM public.playlist_tags t WHERE t.playlist_id=p.id),'[]'::jsonb)
 ) FROM public.playlists p WHERE p.id=p_id;
$$;

CREATE OR REPLACE FUNCTION internal.pin_detail(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id',p.id,'ownerId',p.owner_id,'title',p.title,
   'description',p.description,'latitude',extensions.ST_Y(p.geom::extensions.geometry),
   'longitude',extensions.ST_X(p.geom::extensions.geometry),
   'visibility',p.visibility,'visitDate',p.visit_date,'revision',p.revision,
   'trackIds',COALESCE((SELECT jsonb_agg(t.track_id ORDER BY t.position)
                       FROM public.pin_tracks t WHERE t.pin_id=p.id),'[]'::jsonb),
   'photoPaths',COALESCE((SELECT jsonb_agg(f.storage_path ORDER BY f.position)
                         FROM public.pin_photos f WHERE f.pin_id=p.id),'[]'::jsonb)
 ) FROM public.music_pins p WHERE p.id=p_id;
$$;

CREATE OR REPLACE FUNCTION internal.event_detail(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id',e.id,'ownerId',e.owner_id,'title',e.title,'eventDate',e.event_date,
   'locationName',e.location_name,'body',e.body,'visibility',e.visibility,
   'musicPinId',CASE WHEN e.music_pin_id IS NULL OR internal.can_read_pin(e.music_pin_id)
      THEN e.music_pin_id ELSE NULL END,'revision',e.revision,
   'latitude',CASE WHEN e.geom IS NULL THEN NULL ELSE extensions.ST_Y(e.geom::extensions.geometry) END,
   'longitude',CASE WHEN e.geom IS NULL THEN NULL ELSE extensions.ST_X(e.geom::extensions.geometry) END,
   'trackIds',COALESCE((SELECT jsonb_agg(t.track_id ORDER BY t.position)
                       FROM public.event_tracks t WHERE t.event_id=e.id),'[]'::jsonb),
   'photoPaths',COALESCE((SELECT jsonb_agg(f.storage_path ORDER BY f.position)
                         FROM public.event_photos f WHERE f.event_id=e.id),'[]'::jsonb)
 ) FROM public.travel_events e WHERE e.id=p_id;
$$;

CREATE OR REPLACE FUNCTION internal.journey_detail(p_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT jsonb_build_object(
   'id',j.id,'ownerId',j.owner_id,'title',j.title,
   'playlistId',CASE WHEN j.playlist_id IS NULL OR internal.can_read_playlist(j.playlist_id)
     THEN j.playlist_id ELSE NULL END,'visibility',j.visibility,'revision',j.revision,
   'stops',COALESCE((SELECT jsonb_agg(jsonb_build_object(
          'kind',CASE WHEN jp.place_id IS NOT NULL THEN 'PLACE' ELSE 'PIN' END,
          'id',COALESCE(jp.place_id,jp.pin_id),'position',jp.position)
          ORDER BY jp.position)
         FROM public.journey_places jp WHERE jp.journey_id=j.id
           AND (jp.pin_id IS NULL OR internal.can_read_pin(jp.pin_id))
           AND (jp.place_id IS NULL OR EXISTS (SELECT 1 FROM public.places plc WHERE plc.id=jp.place_id AND plc.is_published))
         ),'[]'::jsonb)
 ) FROM public.music_journeys j WHERE j.id=p_id;
$$;

REVOKE ALL ON FUNCTION internal.track_view(uuid),internal.playlist_detail(uuid),
 internal.playlist_summary(uuid),internal.pin_detail(uuid),internal.event_detail(uuid),
 internal.journey_detail(uuid)
FROM PUBLIC, anon, authenticated;

-- ===========================================================
-- 2. Shared read-only city/pin feed (includes own PRIVATE pins)
-- ===========================================================
CREATE OR REPLACE FUNCTION internal.visible_places()
RETURNS TABLE (
 id uuid, kind text, title text, latitude double precision,
 longitude double precision, description text, playlist_id uuid,
 cover_path text, geom extensions.geography
)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
 SELECT p.id,'CITY'::text,p.title,
   extensions.ST_Y(p.geom::extensions.geometry),
   extensions.ST_X(p.geom::extensions.geometry),
   p.description,
   (SELECT pl.id FROM public.playlists pl WHERE pl.place_id=p.id
       AND pl.is_editorial AND pl.visibility='PUBLIC' AND pl.published_at IS NOT NULL
       ORDER BY pl.published_at DESC LIMIT 1),
   p.cover_path,p.geom
 FROM public.places p WHERE p.is_published
 UNION ALL
 SELECT pin.id,'PIN'::text,pin.title,
   extensions.ST_Y(pin.geom::extensions.geometry),
   extensions.ST_X(pin.geom::extensions.geometry),
   pin.description,NULL::uuid,NULL::text,pin.geom
 FROM public.music_pins pin
 WHERE pin.visibility='PUBLIC' OR pin.owner_id=(SELECT auth.uid());
$$;
REVOKE ALL ON FUNCTION internal.visible_places() FROM PUBLIC,anon,authenticated;

CREATE OR REPLACE FUNCTION public.get_visible_places(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_min_lat double precision; v_min_lon double precision;
        v_max_lat double precision; v_max_lon double precision;
        v_limit integer;v_offset integer;v_total bigint;v_items jsonb;
BEGIN
 PERFORM internal.assert_object($1);
 v_min_lat:=($1->>'minLat')::double precision;
 v_min_lon:=($1->>'minLon')::double precision;
 v_max_lat:=($1->>'maxLat')::double precision;
 v_max_lon:=($1->>'maxLon')::double precision;
 v_limit:=COALESCE(($1->>'limit')::integer,20);
 v_offset:=COALESCE(($1->>'offset')::integer,0);
 PERFORM internal.assert_pagination(v_limit,v_offset);
 IF v_min_lat IS NULL OR v_max_lat IS NULL OR v_min_lon IS NULL OR v_max_lon IS NULL
    OR v_min_lat NOT BETWEEN -90 AND 90 OR v_max_lat NOT BETWEEN -90 AND 90
    OR v_min_lon NOT BETWEEN -180 AND 180 OR v_max_lon NOT BETWEEN -180 AND 180
    OR v_min_lat>v_max_lat OR v_min_lon>v_max_lon THEN
  PERFORM internal.fail('PT422','INVALID_BOUNDS');
 END IF;
 SELECT count(*) INTO v_total FROM internal.visible_places() v
 WHERE v.latitude BETWEEN v_min_lat AND v_max_lat
   AND v.longitude BETWEEN v_min_lon AND v_max_lon;
 SELECT COALESCE(jsonb_agg(jsonb_build_object(
     'id',x.id,'kind',x.kind,'title',x.title,'latitude',x.latitude,
     'longitude',x.longitude,'description',x.description,
     'playlistId',x.playlist_id,'coverPath',x.cover_path) ORDER BY x.kind,x.title,x.id),'[]'::jsonb)
 INTO v_items FROM (
   SELECT * FROM internal.visible_places() v
   WHERE v.latitude BETWEEN v_min_lat AND v_max_lat
      AND v.longitude BETWEEN v_min_lon AND v_max_lon
   ORDER BY v.kind,v.title,v.id LIMIT v_limit OFFSET v_offset
 ) x;
 RETURN jsonb_build_object('items',v_items,'limit',v_limit,'offset',v_offset,
   'hasMore',(v_total>v_offset::bigint+v_limit));
END;$$;

CREATE OR REPLACE FUNCTION public.get_nearby_places(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_lat double precision; v_lon double precision;v_radius integer;
        v_limit integer;v_offset integer;v_total bigint;v_items jsonb;
        v_center extensions.geography;
BEGIN
 PERFORM internal.assert_object($1);
 v_lat:=($1->>'latitude')::double precision;
 v_lon:=($1->>'longitude')::double precision;
 v_radius:=COALESCE(($1->>'radiusM')::integer,1000);
 v_limit:=COALESCE(($1->>'limit')::integer,20);
 v_offset:=COALESCE(($1->>'offset')::integer,0);
 PERFORM internal.assert_pagination(v_limit,v_offset);
 IF v_lat IS NULL OR v_lon IS NULL OR v_lat NOT BETWEEN -90 AND 90
    OR v_lon NOT BETWEEN -180 AND 180 OR v_radius NOT BETWEEN 1 AND 50000 THEN
   PERFORM internal.fail('PT422','INVALID_LOCATION');
 END IF;
 v_center := extensions.ST_SetSRID(extensions.ST_MakePoint(v_lon,v_lat),4326)::extensions.geography;
 SELECT count(*) INTO v_total FROM internal.visible_places() v
 WHERE extensions.ST_DWithin(v.geom,v_center,v_radius);
 SELECT COALESCE(jsonb_agg(jsonb_build_object(
   'id',x.id,'kind',x.kind,'title',x.title,'latitude',x.latitude,
   'longitude',x.longitude,'description',x.description,
   'playlistId',x.playlist_id,'coverPath',x.cover_path)
   ORDER BY x.distance_m,x.id),'[]'::jsonb)
 INTO v_items FROM (
  SELECT v.*,extensions.ST_Distance(v.geom,v_center) AS distance_m
  FROM internal.visible_places() v
  WHERE extensions.ST_DWithin(v.geom,v_center,v_radius)
  ORDER BY distance_m,v.id LIMIT v_limit OFFSET v_offset
 ) x;
 RETURN jsonb_build_object('items',v_items,'limit',v_limit,'offset',v_offset,
  'hasMore',(v_total>v_offset::bigint+v_limit));
END;$$;

CREATE OR REPLACE FUNCTION public.list_playlists(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_scope text;v_limit integer;v_offset integer;v_uid uuid;
        v_total bigint;v_items jsonb;
BEGIN
 PERFORM internal.assert_object($1);
 v_scope:=COALESCE($1->>'scope','PUBLIC');
 v_limit:=COALESCE(($1->>'limit')::integer,20);
 v_offset:=COALESCE(($1->>'offset')::integer,0);
 PERFORM internal.assert_pagination(v_limit,v_offset);
 IF v_scope NOT IN ('PUBLIC','MINE') THEN PERFORM internal.fail('PT422','INVALID_SCOPE'); END IF;
 IF v_scope='MINE' THEN v_uid:=internal.require_user(); ELSE v_uid:=(SELECT auth.uid()); END IF;
 SELECT count(*) INTO v_total FROM public.playlists p WHERE
   (v_scope='MINE' AND p.owner_id=v_uid)
   OR (v_scope='PUBLIC' AND p.visibility='PUBLIC'
       AND (NOT p.is_editorial OR p.published_at IS NOT NULL));
 SELECT COALESCE(jsonb_agg(internal.playlist_summary(x.id) ORDER BY x.updated_at DESC,x.id),'[]'::jsonb)
 INTO v_items FROM (
 SELECT p.id,p.updated_at FROM public.playlists p WHERE
   (v_scope='MINE' AND p.owner_id=v_uid)
   OR (v_scope='PUBLIC' AND p.visibility='PUBLIC'
       AND (NOT p.is_editorial OR p.published_at IS NOT NULL))
 ORDER BY p.updated_at DESC,p.id LIMIT v_limit OFFSET v_offset
 ) x;
 RETURN jsonb_build_object('items',v_items,'limit',v_limit,'offset',v_offset,
  'hasMore',(v_total>v_offset::bigint+v_limit));
END;$$;

CREATE OR REPLACE FUNCTION public.get_playlist(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_id uuid;
BEGIN
 PERFORM internal.assert_object($1);
 v_id:=($1->>'playlistId')::uuid;
 IF v_id IS NULL OR NOT internal.can_read_playlist(v_id) THEN
  PERFORM internal.fail('PT404','PLAYLIST_NOT_FOUND');
 END IF;
 RETURN internal.playlist_detail(v_id);
END;$$;

-- ===========================================================
-- 3. Mutating helpers: atomic full replacement of child arrays
-- ===========================================================
CREATE OR REPLACE FUNCTION internal.replace_playlist_children(p_id uuid,p_req jsonb)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
 PERFORM internal.assert_tracks(p_req->'trackIds',100,false);
 PERFORM internal.assert_ids(p_req->'genreIds','genres',30);
 PERFORM internal.assert_ids(p_req->'tagIds','tags',30);
 DELETE FROM public.playlist_tracks WHERE playlist_id=p_id;
 INSERT INTO public.playlist_tracks(playlist_id,track_id,position)
 SELECT p_id,x.id::uuid,x.ord::integer
 FROM jsonb_array_elements_text(p_req->'trackIds') WITH ORDINALITY AS x(id,ord);
 DELETE FROM public.playlist_genres WHERE playlist_id=p_id;
 INSERT INTO public.playlist_genres(playlist_id,genre_id)
 SELECT p_id,x.id::smallint FROM jsonb_array_elements_text(COALESCE(p_req->'genreIds','[]'::jsonb)) x(id);
 DELETE FROM public.playlist_tags WHERE playlist_id=p_id;
 INSERT INTO public.playlist_tags(playlist_id,tag_id)
 SELECT p_id,x.id::smallint FROM jsonb_array_elements_text(COALESCE(p_req->'tagIds','[]'::jsonb)) x(id);
END;$$;

CREATE OR REPLACE FUNCTION internal.replace_pin_children(p_id uuid,p_req jsonb,p_owner uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
 PERFORM internal.assert_tracks(p_req->'trackIds',100,true);
 PERFORM internal.assert_media_array(p_req->'photoPaths',p_owner,5);
 DELETE FROM public.pin_tracks WHERE pin_id=p_id;
 INSERT INTO public.pin_tracks(pin_id,track_id,position)
 SELECT p_id,x.id::uuid,x.ord::integer FROM jsonb_array_elements_text(p_req->'trackIds') WITH ORDINALITY x(id,ord);
 DELETE FROM public.pin_photos WHERE pin_id=p_id;
 INSERT INTO public.pin_photos(pin_id,storage_path,position)
 SELECT p_id,x.path,x.ord::integer FROM jsonb_array_elements_text(COALESCE(p_req->'photoPaths','[]'::jsonb)) WITH ORDINALITY x(path,ord);
END;$$;

CREATE OR REPLACE FUNCTION internal.replace_event_children(p_id uuid,p_req jsonb,p_owner uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
 PERFORM internal.assert_tracks(p_req->'trackIds',100,true);
 PERFORM internal.assert_media_array(p_req->'photoPaths',p_owner,10);
 DELETE FROM public.event_tracks WHERE event_id=p_id;
 INSERT INTO public.event_tracks(event_id,track_id,position)
 SELECT p_id,x.id::uuid,x.ord::integer FROM jsonb_array_elements_text(p_req->'trackIds') WITH ORDINALITY x(id,ord);
 DELETE FROM public.event_photos WHERE event_id=p_id;
 INSERT INTO public.event_photos(event_id,storage_path,position)
 SELECT p_id,x.path,x.ord::integer FROM jsonb_array_elements_text(COALESCE(p_req->'photoPaths','[]'::jsonb)) WITH ORDINALITY x(path,ord);
END;$$;

CREATE OR REPLACE FUNCTION internal.replace_journey_stops(p_id uuid,p_req jsonb,p_visibility text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_stop jsonb;v_kind text;v_id uuid;v_position int;v_count integer;v_uid uuid;
BEGIN
 v_uid:=internal.require_user();
 IF jsonb_typeof(p_req->'stops') <> 'array' THEN PERFORM internal.fail('PT422','STOPS_REQUIRED'); END IF;
 v_count:=jsonb_array_length(p_req->'stops');
 IF v_count NOT BETWEEN 2 AND 50 THEN PERFORM internal.fail('PT422','INVALID_STOP_COUNT'); END IF;
 v_position:=0;
 FOR v_stop IN SELECT value FROM jsonb_array_elements(p_req->'stops') LOOP
   v_position:=v_position+1;
   IF jsonb_typeof(v_stop)<>'object' THEN PERFORM internal.fail('PT422','INVALID_STOP'); END IF;
   v_kind:=v_stop->>'kind';v_id:=(v_stop->>'id')::uuid;
   IF v_id IS NULL OR (v_stop->>'position')::integer IS DISTINCT FROM v_position THEN
      PERFORM internal.fail('PT422','INVALID_STOP_POSITION');
   END IF;
   IF v_kind='PLACE' THEN
     IF NOT EXISTS (SELECT 1 FROM public.places p WHERE p.id=v_id AND p.is_published) THEN
       PERFORM internal.fail('PT422','INVALID_PLACE_STOP');
     END IF;
   ELSIF v_kind='PIN' THEN
     IF NOT EXISTS (SELECT 1 FROM public.music_pins pin WHERE pin.id=v_id
          AND (pin.visibility='PUBLIC' OR (p_visibility='PRIVATE' AND pin.owner_id=v_uid))) THEN
       PERFORM internal.fail('PT422','INVALID_PIN_STOP');
     END IF;
   ELSE
     PERFORM internal.fail('PT422','INVALID_STOP_KIND');
   END IF;
 END LOOP;
 DELETE FROM public.journey_places WHERE journey_id=p_id;
 INSERT INTO public.journey_places(journey_id,place_id,pin_id,position)
 SELECT p_id,
     CASE WHEN x.item->>'kind'='PLACE' THEN (x.item->>'id')::uuid ELSE NULL END,
     CASE WHEN x.item->>'kind'='PIN' THEN (x.item->>'id')::uuid ELSE NULL END,
     x.ord::integer
 FROM jsonb_array_elements(p_req->'stops') WITH ORDINALITY x(item,ord);
END;$$;

REVOKE ALL ON FUNCTION internal.replace_playlist_children(uuid,jsonb),
 internal.replace_pin_children(uuid,jsonb,uuid),
 internal.replace_event_children(uuid,jsonb,uuid),
 internal.replace_journey_stops(uuid,jsonb,text)
FROM PUBLIC,anon,authenticated;

-- ===========================================================
-- 4. Core write RPCs (each call runs in one PostgreSQL transaction)
-- ===========================================================
CREATE OR REPLACE FUNCTION public.save_playlist(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_revision integer;v_owner uuid;v_old_revision integer;
        v_title text;v_vis text;v_cover text;
BEGIN
 PERFORM internal.assert_object($1);
 v_uid:=internal.require_user();
 v_title:=$1->>'title';v_vis:=$1->>'visibility';v_cover:=$1->>'coverPath';
 PERFORM internal.assert_title(v_title); PERFORM internal.assert_visibility(v_vis);
 IF char_length(COALESCE($1->>'description',''))>2000 THEN PERFORM internal.fail('PT422','DESCRIPTION_TOO_LONG'); END IF;
 PERFORM internal.assert_tracks($1->'trackIds',100,false);
 PERFORM internal.assert_ids($1->'genreIds','genres',30);
 PERFORM internal.assert_ids($1->'tagIds','tags',30);
 IF v_cover IS NOT NULL THEN PERFORM internal.assert_media(v_cover,v_uid); END IF;
 v_id:=COALESCE(($1->>'playlistId')::uuid,gen_random_uuid());
 v_revision:=($1->>'expectedRevision')::integer;
 IF v_revision IS NULL THEN
   INSERT INTO public.playlists(id,owner_id,title,description,cover_path,visibility,is_editorial)
   VALUES (v_id,v_uid,v_title,$1->>'description',v_cover,v_vis,false)
   ON CONFLICT (id) DO NOTHING;
   IF NOT FOUND THEN PERFORM internal.fail('PT409','OBJECT_ALREADY_EXISTS'); END IF;
 ELSE
   IF v_revision<1 OR NOT ($1 ? 'playlistId') THEN PERFORM internal.fail('PT422','INVALID_REVISION'); END IF;
   SELECT p.owner_id,p.revision INTO v_owner,v_old_revision FROM public.playlists p WHERE p.id=v_id FOR UPDATE;
   IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','PLAYLIST_NOT_FOUND'); END IF;
   IF v_old_revision<>v_revision THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
   IF EXISTS (SELECT 1 FROM public.playlists p WHERE p.id=v_id AND p.is_editorial) THEN
     PERFORM internal.fail('PT403','EDITORIAL_PLAYLIST_IMMUTABLE_HERE');
   END IF;
   UPDATE public.playlists SET title=v_title,description=$1->>'description',
     cover_path=v_cover,visibility=v_vis,revision=revision+1,updated_at=now()
   WHERE id=v_id;
 END IF;
 PERFORM internal.replace_playlist_children(v_id,$1);
 RETURN internal.playlist_detail(v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.delete_playlist(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_expected integer;v_owner uuid;v_revision integer;v_editorial boolean;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_id:=($1->>'id')::uuid;v_expected:=($1->>'expectedRevision')::integer;
 IF v_id IS NULL OR v_expected IS NULL OR v_expected<1 THEN PERFORM internal.fail('PT422','INVALID_DELETE_REQUEST'); END IF;
 SELECT p.owner_id,p.revision,p.is_editorial INTO v_owner,v_revision,v_editorial FROM public.playlists p WHERE p.id=v_id FOR UPDATE;
 IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','PLAYLIST_NOT_FOUND'); END IF;
 IF v_revision<>v_expected THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
 IF v_editorial THEN PERFORM internal.fail('PT403','EDITORIAL_PLAYLIST_IMMUTABLE_HERE'); END IF;
 DELETE FROM public.playlists WHERE id=v_id;
 RETURN jsonb_build_object('success',true,'id',v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.save_music_pin(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_revision integer;v_owner uuid;v_old_revision integer;
        v_lat double precision;v_lon double precision;v_title text;v_vis text;
        v_geom extensions.geography;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_title:=$1->>'title';v_vis:=$1->>'visibility';
 v_lat:=($1->>'latitude')::double precision;v_lon:=($1->>'longitude')::double precision;
 PERFORM internal.assert_title(v_title);PERFORM internal.assert_visibility(v_vis);
 IF v_lat IS NULL OR v_lat NOT BETWEEN -90 AND 90 OR v_lon IS NULL OR v_lon NOT BETWEEN -180 AND 180 THEN
   PERFORM internal.fail('PT422','INVALID_COORDINATES');
 END IF;
 v_geom:=extensions.ST_SetSRID(extensions.ST_MakePoint(v_lon,v_lat),4326)::extensions.geography;
 PERFORM internal.assert_tracks($1->'trackIds',100,true);
 PERFORM internal.assert_media_array($1->'photoPaths',v_uid,5);
 v_id:=COALESCE(($1->>'id')::uuid,gen_random_uuid());v_revision:=($1->>'expectedRevision')::integer;
 IF v_revision IS NULL THEN
   INSERT INTO public.music_pins(id,owner_id,title,description,geom,visit_date,visibility)
   VALUES(v_id,v_uid,v_title,$1->>'description',v_geom,($1->>'visitDate')::date,v_vis)
   ON CONFLICT(id) DO NOTHING;
   IF NOT FOUND THEN PERFORM internal.fail('PT409','OBJECT_ALREADY_EXISTS'); END IF;
 ELSE
   IF v_revision<1 OR NOT ($1 ? 'id') THEN PERFORM internal.fail('PT422','INVALID_REVISION'); END IF;
   SELECT p.owner_id,p.revision INTO v_owner,v_old_revision FROM public.music_pins p WHERE p.id=v_id FOR UPDATE;
   IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','PIN_NOT_FOUND'); END IF;
   IF v_old_revision<>v_revision THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
   UPDATE public.music_pins SET title=v_title,description=$1->>'description',geom=v_geom,
     visit_date=($1->>'visitDate')::date,visibility=v_vis,revision=revision+1,updated_at=now() WHERE id=v_id;
 END IF;
 PERFORM internal.replace_pin_children(v_id,$1,v_uid);
 RETURN internal.pin_detail(v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.save_travel_event(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_revision integer;v_owner uuid;v_old_revision integer;
        v_lat double precision;v_lon double precision;v_geom extensions.geography;
        v_title text;v_vis text;v_pin uuid;v_loc text;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_title:=$1->>'title';v_vis:=COALESCE($1->>'visibility','PRIVATE');
 v_loc:=$1->>'locationName';v_pin:=($1->>'musicPinId')::uuid;
 v_lat:=($1->>'latitude')::double precision;v_lon:=($1->>'longitude')::double precision;
 PERFORM internal.assert_title(v_title);PERFORM internal.assert_visibility(v_vis);
 IF ($1->>'eventDate') IS NULL THEN PERFORM internal.fail('PT422','EVENT_DATE_REQUIRED'); END IF;
 IF (v_lat IS NULL) <> (v_lon IS NULL) THEN PERFORM internal.fail('PT422','BOTH_COORDINATES_REQUIRED'); END IF;
 IF v_lat IS NOT NULL AND (v_lat NOT BETWEEN -90 AND 90 OR v_lon NOT BETWEEN -180 AND 180) THEN
   PERFORM internal.fail('PT422','INVALID_COORDINATES');
 END IF;
 IF v_loc IS NOT NULL AND btrim(v_loc)='' THEN PERFORM internal.fail('PT422','INVALID_LOCATION_NAME'); END IF;
 IF v_loc IS NULL AND v_lat IS NULL AND v_pin IS NULL THEN PERFORM internal.fail('PT422','LOCATION_REQUIRED'); END IF;
 IF v_pin IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM public.music_pins p WHERE p.id=v_pin AND
     (p.visibility='PUBLIC' OR (v_vis='PRIVATE' AND p.owner_id=v_uid))
 ) THEN PERFORM internal.fail('PT422','INVALID_PIN_REFERENCE'); END IF;
 IF v_lat IS NOT NULL THEN
   v_geom:=extensions.ST_SetSRID(extensions.ST_MakePoint(v_lon,v_lat),4326)::extensions.geography;
 END IF;
 PERFORM internal.assert_tracks($1->'trackIds',100,true);
 PERFORM internal.assert_media_array($1->'photoPaths',v_uid,10);
 v_id:=COALESCE(($1->>'id')::uuid,gen_random_uuid());v_revision:=($1->>'expectedRevision')::integer;
 IF v_revision IS NULL THEN
   INSERT INTO public.travel_events(id,owner_id,music_pin_id,title,event_date,location_name,geom,body,visibility)
   VALUES(v_id,v_uid,v_pin,v_title,($1->>'eventDate')::date,v_loc,v_geom,$1->>'body',v_vis)
   ON CONFLICT(id) DO NOTHING;
   IF NOT FOUND THEN PERFORM internal.fail('PT409','OBJECT_ALREADY_EXISTS'); END IF;
 ELSE
   IF v_revision<1 OR NOT ($1 ? 'id') THEN PERFORM internal.fail('PT422','INVALID_REVISION'); END IF;
   SELECT e.owner_id,e.revision INTO v_owner,v_old_revision FROM public.travel_events e WHERE e.id=v_id FOR UPDATE;
   IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','EVENT_NOT_FOUND'); END IF;
   IF v_old_revision<>v_revision THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
   UPDATE public.travel_events SET music_pin_id=v_pin,title=v_title,event_date=($1->>'eventDate')::date,
     location_name=v_loc,geom=v_geom,body=$1->>'body',visibility=v_vis,
     revision=revision+1,updated_at=now() WHERE id=v_id;
 END IF;
 PERFORM internal.replace_event_children(v_id,$1,v_uid);
 RETURN internal.event_detail(v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.list_travel_events(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_limit integer;v_offset integer;v_total bigint;v_items jsonb;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_limit:=COALESCE(($1->>'limit')::integer,20);
 v_offset:=COALESCE(($1->>'offset')::integer,0);
 PERFORM internal.assert_pagination(v_limit,v_offset);
 SELECT count(*) INTO v_total FROM public.travel_events e WHERE e.owner_id=v_uid;
 SELECT COALESCE(jsonb_agg(internal.event_detail(x.id) ORDER BY x.event_date DESC,x.id),'[]'::jsonb)
 INTO v_items FROM (
   SELECT e.id,e.event_date FROM public.travel_events e WHERE e.owner_id=v_uid
   ORDER BY e.event_date DESC,e.id LIMIT v_limit OFFSET v_offset
 ) x;
 RETURN jsonb_build_object('items',v_items,'limit',v_limit,'offset',v_offset,
  'hasMore',(v_total>v_offset::bigint+v_limit));
END;$$;

CREATE OR REPLACE FUNCTION public.save_music_journey(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_revision integer;v_owner uuid;v_old_revision integer;
        v_title text;v_vis text;v_playlist uuid;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_title:=$1->>'title';v_vis:=$1->>'visibility';v_playlist:=($1->>'playlistId')::uuid;
 PERFORM internal.assert_title(v_title);PERFORM internal.assert_visibility(v_vis);
 IF v_playlist IS NOT NULL AND NOT EXISTS (
   SELECT 1 FROM public.playlists p WHERE p.id=v_playlist AND
     (p.owner_id=v_uid OR (p.visibility='PUBLIC' AND (NOT p.is_editorial OR p.published_at IS NOT NULL)))
     AND (v_vis='PRIVATE' OR (p.visibility='PUBLIC' AND (NOT p.is_editorial OR p.published_at IS NOT NULL)))
 ) THEN PERFORM internal.fail('PT422','INVALID_PLAYLIST_REFERENCE'); END IF;
 v_id:=COALESCE(($1->>'id')::uuid,gen_random_uuid());v_revision:=($1->>'expectedRevision')::integer;
 -- Validate all stops before a single write.
 IF jsonb_typeof($1->'stops') <> 'array' OR jsonb_array_length($1->'stops') NOT BETWEEN 2 AND 50 THEN
   PERFORM internal.fail('PT422','INVALID_STOP_COUNT');
 END IF;
 IF v_revision IS NULL THEN
   INSERT INTO public.music_journeys(id,owner_id,playlist_id,title,visibility)
   VALUES(v_id,v_uid,v_playlist,v_title,v_vis) ON CONFLICT(id) DO NOTHING;
   IF NOT FOUND THEN PERFORM internal.fail('PT409','OBJECT_ALREADY_EXISTS'); END IF;
 ELSE
   IF v_revision<1 OR NOT ($1 ? 'id') THEN PERFORM internal.fail('PT422','INVALID_REVISION'); END IF;
   SELECT j.owner_id,j.revision INTO v_owner,v_old_revision FROM public.music_journeys j WHERE j.id=v_id FOR UPDATE;
   IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','JOURNEY_NOT_FOUND'); END IF;
   IF v_old_revision<>v_revision THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
   UPDATE public.music_journeys SET title=v_title,visibility=v_vis,playlist_id=v_playlist,
      revision=revision+1,updated_at=now() WHERE id=v_id;
 END IF;
 PERFORM internal.replace_journey_stops(v_id,$1,v_vis);
 RETURN internal.journey_detail(v_id);
END;$$;

-- ===========================================================
-- 5. Supporting CRUD (additional operations, not part of original 15)
-- ===========================================================
CREATE OR REPLACE FUNCTION public.get_music_pin(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_id uuid;
BEGIN
 PERFORM internal.assert_object($1);v_id:=($1->>'id')::uuid;
 IF v_id IS NULL OR NOT internal.can_read_pin(v_id) THEN PERFORM internal.fail('PT404','PIN_NOT_FOUND'); END IF;
 RETURN internal.pin_detail(v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.get_travel_event(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_id uuid;
BEGIN
 PERFORM internal.assert_object($1);v_id:=($1->>'id')::uuid;
 IF v_id IS NULL OR NOT internal.can_read_event(v_id) THEN PERFORM internal.fail('PT404','EVENT_NOT_FOUND'); END IF;
 RETURN internal.event_detail(v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.get_music_journey(jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_id uuid;
BEGIN
 PERFORM internal.assert_object($1);v_id:=($1->>'id')::uuid;
 IF v_id IS NULL OR NOT internal.can_read_journey(v_id) THEN PERFORM internal.fail('PT404','JOURNEY_NOT_FOUND'); END IF;
 RETURN internal.journey_detail(v_id);
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
 WHERE (v_scope='MINE' AND j.owner_id=v_uid) OR (v_scope='PUBLIC' AND j.visibility='PUBLIC');
 SELECT COALESCE(jsonb_agg(internal.journey_detail(x.id) ORDER BY x.updated_at DESC,x.id),'[]'::jsonb)
 INTO v_items FROM (
   SELECT j.id,j.updated_at FROM public.music_journeys j
   WHERE (v_scope='MINE' AND j.owner_id=v_uid) OR (v_scope='PUBLIC' AND j.visibility='PUBLIC')
   ORDER BY j.updated_at DESC,j.id LIMIT v_limit OFFSET v_offset
 ) x;
 RETURN jsonb_build_object('items',v_items,'limit',v_limit,'offset',v_offset,
   'hasMore',(v_total>v_offset::bigint+v_limit));
END;$$;

CREATE OR REPLACE FUNCTION public.delete_music_pin(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_expected integer;v_owner uuid;v_revision integer;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_id:=($1->>'id')::uuid;v_expected:=($1->>'expectedRevision')::integer;
 IF v_id IS NULL OR v_expected IS NULL OR v_expected<1 THEN PERFORM internal.fail('PT422','INVALID_DELETE_REQUEST'); END IF;
 SELECT p.owner_id,p.revision INTO v_owner,v_revision FROM public.music_pins p WHERE p.id=v_id FOR UPDATE;
 IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','PIN_NOT_FOUND'); END IF;
 IF v_revision<>v_expected THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
 -- References from private events and journeys need deliberate management, not accidental cascades.
 IF EXISTS(SELECT 1 FROM public.travel_events e WHERE e.music_pin_id=v_id)
    OR EXISTS(SELECT 1 FROM public.journey_places j WHERE j.pin_id=v_id) THEN
   PERFORM internal.fail('PT409','PIN_REFERENCED');
 END IF;
 DELETE FROM public.music_pins WHERE id=v_id;
 RETURN jsonb_build_object('success',true,'id',v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.delete_travel_event(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_expected integer;v_owner uuid;v_revision integer;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_id:=($1->>'id')::uuid;v_expected:=($1->>'expectedRevision')::integer;
 IF v_id IS NULL OR v_expected IS NULL OR v_expected<1 THEN PERFORM internal.fail('PT422','INVALID_DELETE_REQUEST'); END IF;
 SELECT e.owner_id,e.revision INTO v_owner,v_revision FROM public.travel_events e WHERE e.id=v_id FOR UPDATE;
 IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','EVENT_NOT_FOUND'); END IF;
 IF v_revision<>v_expected THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
 DELETE FROM public.travel_events WHERE id=v_id;
 RETURN jsonb_build_object('success',true,'id',v_id);
END;$$;

CREATE OR REPLACE FUNCTION public.delete_music_journey(jsonb)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_uid uuid;v_id uuid;v_expected integer;v_owner uuid;v_revision integer;
BEGIN
 PERFORM internal.assert_object($1);v_uid:=internal.require_user();
 v_id:=($1->>'id')::uuid;v_expected:=($1->>'expectedRevision')::integer;
 IF v_id IS NULL OR v_expected IS NULL OR v_expected<1 THEN PERFORM internal.fail('PT422','INVALID_DELETE_REQUEST'); END IF;
 SELECT j.owner_id,j.revision INTO v_owner,v_revision FROM public.music_journeys j WHERE j.id=v_id FOR UPDATE;
 IF NOT FOUND OR v_owner IS DISTINCT FROM v_uid THEN PERFORM internal.fail('PT404','JOURNEY_NOT_FOUND'); END IF;
 IF v_revision<>v_expected THEN PERFORM internal.fail('PT409','VERSION_CONFLICT'); END IF;
 DELETE FROM public.music_journeys WHERE id=v_id;
 RETURN jsonb_build_object('success',true,'id',v_id);
END;$$;

-- ===========================================================
-- 6. Grant EXACTLY the exported RPCs. Never expose internal.*.
-- ===========================================================
REVOKE ALL ON FUNCTION
 public.get_visible_places(jsonb), public.get_nearby_places(jsonb),
 public.list_playlists(jsonb), public.get_playlist(jsonb),
 public.save_playlist(jsonb), public.delete_playlist(jsonb),
 public.save_music_pin(jsonb), public.save_travel_event(jsonb),
 public.list_travel_events(jsonb), public.save_music_journey(jsonb),
 public.get_music_pin(jsonb), public.get_travel_event(jsonb),
 public.get_music_journey(jsonb), public.list_music_journeys(jsonb),
 public.delete_music_pin(jsonb), public.delete_travel_event(jsonb),
 public.delete_music_journey(jsonb)
FROM PUBLIC,anon,authenticated;

GRANT EXECUTE ON FUNCTION
 public.get_visible_places(jsonb), public.get_nearby_places(jsonb),
 public.list_playlists(jsonb), public.get_playlist(jsonb),
 public.get_music_pin(jsonb), public.get_travel_event(jsonb),
 public.get_music_journey(jsonb), public.list_music_journeys(jsonb)
TO anon,authenticated;

GRANT EXECUTE ON FUNCTION
 public.save_playlist(jsonb), public.delete_playlist(jsonb),
 public.save_music_pin(jsonb), public.save_travel_event(jsonb),
 public.list_travel_events(jsonb), public.save_music_journey(jsonb),
 public.delete_music_pin(jsonb),public.delete_travel_event(jsonb),
 public.delete_music_journey(jsonb)
TO authenticated;

-- Ask PostgREST to refresh its schema cache after the migration commits.
NOTIFY pgrst, 'reload schema';
