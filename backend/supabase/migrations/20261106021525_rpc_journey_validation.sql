-- SoundScape Block B correction: reject missing/JSON-null journey stops.
-- Applied after 20261106020855_rpc_crud.sql; local only until tested.
-- PostgreSQL IF treats NULL conditions as false. The previous <> check
-- incorrectly allowed absent stops to bypass the required-array test.
-- CREATE OR REPLACE preserves the existing function signature/grants.

CREATE OR REPLACE FUNCTION internal.replace_journey_stops(p_id uuid,p_req jsonb,p_visibility text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_stop jsonb;v_kind text;v_id uuid;v_position int;v_count integer;v_uid uuid;
BEGIN
 v_uid:=internal.require_user();
 IF jsonb_typeof(p_req->'stops') IS DISTINCT FROM 'array' THEN PERFORM internal.fail('PT422','STOPS_REQUIRED'); END IF;
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

