-- SoundScape follow-up migration: preserve photo UUIDs when the same Storage
-- object remains attached to a music pin or a travel event.
-- Apply only after initial_schema, auth_rls_storage, rpc_crud,
-- rpc_journey_validation, edge_support, fix_antimeridian.
--
-- Previous replace_*_children() deleted and reinserted all photo rows,
-- generating a fresh pin_photos.id / event_photos.id on every edit.
-- The client correctly kept the previously returned photoId; v1-media then
-- returned 404 even for a valid, active UNLISTED share token.
--
-- Security properties preserved: same function owner, SECURITY DEFINER,
-- empty search_path, server-side storage_path ownership verification,
-- existing row/position constraints and existing EXECUTE grants.

CREATE OR REPLACE FUNCTION internal.replace_pin_children(
    p_id uuid, p_req jsonb, p_owner uuid
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_previous_photo_ids jsonb;
BEGIN
    -- Validate everything before making changes.
    PERFORM internal.assert_tracks(p_req->'trackIds', 100, true);
    PERFORM internal.assert_media_array(p_req->'photoPaths', p_owner, 5);

    -- Preserve existing identifiers by exact Storage path for this pin only.
    SELECT COALESCE(
        pg_catalog.jsonb_object_agg(ph.storage_path, ph.id::text),
        '{}'::jsonb
    )
    INTO v_previous_photo_ids
    FROM public.pin_photos AS ph
    WHERE ph.pin_id = p_id;

    DELETE FROM public.pin_tracks WHERE pin_id = p_id;
    INSERT INTO public.pin_tracks (pin_id, track_id, position)
    SELECT p_id, x.id::uuid, x.ord::integer
    FROM pg_catalog.jsonb_array_elements_text(p_req->'trackIds')
         WITH ORDINALITY AS x(id, ord);

    -- Rebuild the ordering atomically, retaining UUIDs for unchanged paths.
    -- Truly new photos still receive a new UUID.
    DELETE FROM public.pin_photos WHERE pin_id = p_id;
    INSERT INTO public.pin_photos (id, pin_id, storage_path, position)
    SELECT
        COALESCE(
            (v_previous_photo_ids ->> x.path)::uuid,
            pg_catalog.gen_random_uuid()
        ),
        p_id,
        x.path,
        x.ord::integer
    FROM pg_catalog.jsonb_array_elements_text(
        COALESCE(p_req->'photoPaths', '[]'::jsonb)
    ) WITH ORDINALITY AS x(path, ord);
END;
$$;

CREATE OR REPLACE FUNCTION internal.replace_event_children(
    p_id uuid, p_req jsonb, p_owner uuid
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_previous_photo_ids jsonb;
BEGIN
    PERFORM internal.assert_tracks(p_req->'trackIds', 100, true);
    PERFORM internal.assert_media_array(p_req->'photoPaths', p_owner, 10);

    SELECT COALESCE(
        pg_catalog.jsonb_object_agg(ph.storage_path, ph.id::text),
        '{}'::jsonb
    )
    INTO v_previous_photo_ids
    FROM public.event_photos AS ph
    WHERE ph.event_id = p_id;

    DELETE FROM public.event_tracks WHERE event_id = p_id;
    INSERT INTO public.event_tracks (event_id, track_id, position)
    SELECT p_id, x.id::uuid, x.ord::integer
    FROM pg_catalog.jsonb_array_elements_text(p_req->'trackIds')
         WITH ORDINALITY AS x(id, ord);

    DELETE FROM public.event_photos WHERE event_id = p_id;
    INSERT INTO public.event_photos (id, event_id, storage_path, position)
    SELECT
        COALESCE(
            (v_previous_photo_ids ->> x.path)::uuid,
            pg_catalog.gen_random_uuid()
        ),
        p_id,
        x.path,
        x.ord::integer
    FROM pg_catalog.jsonb_array_elements_text(
        COALESCE(p_req->'photoPaths', '[]'::jsonb)
    ) WITH ORDINALITY AS x(path, ord);
END;
$$;

-- Never grant direct EXECUTE to application API roles.
REVOKE ALL ON FUNCTION
    internal.replace_pin_children(uuid, jsonb, uuid),
    internal.replace_event_children(uuid, jsonb, uuid)
FROM PUBLIC, anon, authenticated;
