-- SoundScape / correction for RPC get_visible_places (OpenAPI 1.1.0).
-- Supports bounding boxes crossing 180th meridian when minLon > maxLon.
-- Apply as a NEW migration after previous migrations; never alter applied files.
-- CREATE OR REPLACE retains existing owner and EXECUTE privileges.

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
    OR v_min_lat>v_max_lat THEN
  PERFORM internal.fail('PT422','INVALID_BOUNDS');
 END IF;
 SELECT count(*) INTO v_total FROM internal.visible_places() v
 WHERE v.latitude BETWEEN v_min_lat AND v_max_lat
   AND (
      (v_min_lon <= v_max_lon AND v.longitude BETWEEN v_min_lon AND v_max_lon)
      OR (v_min_lon > v_max_lon AND (v.longitude >= v_min_lon OR v.longitude <= v_max_lon))
   );
 SELECT COALESCE(jsonb_agg(jsonb_build_object(
     'id',x.id,'kind',x.kind,'title',x.title,'latitude',x.latitude,
     'longitude',x.longitude,'description',x.description,
     'playlistId',x.playlist_id,'coverPath',x.cover_path) ORDER BY x.kind,x.title,x.id),'[]'::jsonb)
 INTO v_items FROM (
   SELECT * FROM internal.visible_places() v
   WHERE v.latitude BETWEEN v_min_lat AND v_max_lat
      AND (
        (v_min_lon <= v_max_lon AND v.longitude BETWEEN v_min_lon AND v_max_lon)
        OR (v_min_lon > v_max_lon AND (v.longitude >= v_min_lon OR v.longitude <= v_max_lon))
      )
   ORDER BY v.kind,v.title,v.id LIMIT v_limit OFFSET v_offset
 ) x;
 RETURN jsonb_build_object('items',v_items,'limit',v_limit,'offset',v_offset,
   'hasMore',(v_total>v_offset::bigint+v_limit));
END;$$;


