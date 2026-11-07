
-- SoundScape / Security foundation
-- Auth profiles, roles, RLS, grants, private Storage.
-- Applies after initial_schema.

-- ============================================
-- 1. Explicit API permissions
-- ============================================

REVOKE ALL ON ALL TABLES IN SCHEMA public
FROM anon, authenticated;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public
FROM PUBLIC, anon, authenticated;

REVOKE ALL ON ALL TABLES IN SCHEMA internal
FROM PUBLIC, anon, authenticated;

GRANT USAGE ON SCHEMA public TO anon, authenticated;

-- Internal schema is NOT exposed via PostgREST.
-- USAGE allows calling specifically granted
-- RLS helper functions, not reading its tables.
GRANT USAGE ON SCHEMA internal TO anon, authenticated;

-- ============================================
-- 2. Trusted role check
-- ============================================

CREATE FUNCTION internal.is_editor()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = (SELECT auth.uid())
          AND p.role_code IN ('EDITOR', 'ADMIN')
    );
$$;

REVOKE ALL ON FUNCTION internal.is_editor()
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION internal.is_editor()
TO anon, authenticated;

-- ============================================
-- 3. Create profile automatically after sign-up
-- ============================================

CREATE FUNCTION internal.handle_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    INSERT INTO public.profiles (
        id,
        username,
        role_code
    )
    VALUES (
        NEW.id,
        'u_' || replace(NEW.id::text, '-', ''),
        'USER'
    );

    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION internal.handle_new_user()
FROM PUBLIC, anon, authenticated;

CREATE TRIGGER on_auth_user_created_soundscape
AFTER INSERT ON auth.users
FOR EACH ROW
EXECUTE FUNCTION internal.handle_new_user();

-- ============================================
-- 4. Visibility helper functions
-- ============================================

CREATE FUNCTION internal.can_read_playlist(p_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.playlists p
        WHERE p.id = p_id
          AND (
              p.owner_id = (SELECT auth.uid())
              OR (
                  p.visibility = 'PUBLIC'
                  AND (
                      NOT p.is_editorial
                      OR p.published_at IS NOT NULL
                  )
              )
          )
    );
$$;

CREATE FUNCTION internal.can_read_pin(p_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.music_pins p
        WHERE p.id = p_id
          AND (
              p.owner_id = (SELECT auth.uid())
              OR p.visibility = 'PUBLIC'
          )
    );
$$;

CREATE FUNCTION internal.can_read_event(p_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.travel_events e
        WHERE e.id = p_id
          AND (
              e.owner_id = (SELECT auth.uid())
              OR e.visibility = 'PUBLIC'
          )
    );
$$;

CREATE FUNCTION internal.can_read_journey(p_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.music_journeys j
        WHERE j.id = p_id
          AND (
              j.owner_id = (SELECT auth.uid())
              OR j.visibility = 'PUBLIC'
          )
    );
$$;

REVOKE ALL ON FUNCTION
    internal.can_read_playlist(uuid),
    internal.can_read_pin(uuid),
    internal.can_read_event(uuid),
    internal.can_read_journey(uuid)
FROM PUBLIC;

GRANT EXECUTE ON FUNCTION
    internal.can_read_playlist(uuid),
    internal.can_read_pin(uuid),
    internal.can_read_event(uuid),
    internal.can_read_journey(uuid)
TO anon, authenticated;

-- ============================================
-- 5. Roles and profiles
-- ============================================

GRANT SELECT ON public.roles
TO anon, authenticated;

CREATE POLICY roles_select
ON public.roles
FOR SELECT TO anon, authenticated
USING (true);

-- The role_code column is deliberately NOT
-- granted to public API clients.

GRANT SELECT (id, username, created_at)
ON public.profiles
TO anon, authenticated;

GRANT UPDATE (username)
ON public.profiles
TO authenticated;

CREATE POLICY profiles_select
ON public.profiles
FOR SELECT TO anon, authenticated
USING (true);

CREATE POLICY profiles_update_own
ON public.profiles
FOR UPDATE TO authenticated
USING (id = (SELECT auth.uid()))
WITH CHECK (id = (SELECT auth.uid()));

-- ============================================
-- 6. Public music catalog (read-only)
-- ============================================

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'artists',
        'genres',
        'moods',
        'tags',
        'tracks',
        'track_artists',
        'track_genres',
        'track_moods'
    ]
    LOOP
        EXECUTE format(
            'GRANT SELECT ON public.%I TO anon, authenticated',
            t
        );

        EXECUTE format(
            'CREATE POLICY catalog_select ON public.%I
             FOR SELECT TO anon, authenticated
             USING (true)',
            t
        );
    END LOOP;
END;
$$;

-- track_sources is intentionally not exposed.
-- Verified source metadata is accessed through
-- protected server-side operations.

-- ============================================
-- 7. Editorial places
-- ============================================

GRANT SELECT ON public.places
TO anon, authenticated;

GRANT INSERT, UPDATE, DELETE ON public.places
TO authenticated;

CREATE POLICY places_select
ON public.places
FOR SELECT TO anon, authenticated
USING (
    is_published
    OR (SELECT internal.is_editor())
);

CREATE POLICY places_insert_editor
ON public.places
FOR INSERT TO authenticated
WITH CHECK ((SELECT internal.is_editor()));

CREATE POLICY places_update_editor
ON public.places
FOR UPDATE TO authenticated
USING ((SELECT internal.is_editor()))
WITH CHECK ((SELECT internal.is_editor()));

CREATE POLICY places_delete_editor
ON public.places
FOR DELETE TO authenticated
USING ((SELECT internal.is_editor()));

-- ============================================
-- 8. Playlists and child tables
-- ============================================

GRANT SELECT ON public.playlists
TO anon, authenticated;

CREATE POLICY playlists_select
ON public.playlists
FOR SELECT TO anon, authenticated
USING (internal.can_read_playlist(id));

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'playlist_tracks',
        'playlist_genres',
        'playlist_tags'
    ]
    LOOP
        EXECUTE format(
            'GRANT SELECT ON public.%I TO anon, authenticated',
            t
        );

        EXECUTE format(
            'CREATE POLICY playlist_children_select
             ON public.%I
             FOR SELECT TO anon, authenticated
             USING (
               internal.can_read_playlist(playlist_id)
             )',
            t
        );
    END LOOP;
END;
$$;

-- ============================================
-- 9. Music pins and child tables
-- ============================================

GRANT SELECT ON public.music_pins
TO anon, authenticated;

CREATE POLICY music_pins_select
ON public.music_pins
FOR SELECT TO anon, authenticated
USING (internal.can_read_pin(id));

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'pin_tracks',
        'pin_photos'
    ]
    LOOP
        EXECUTE format(
            'GRANT SELECT ON public.%I TO anon, authenticated',
            t
        );

        EXECUTE format(
            'CREATE POLICY pin_children_select
             ON public.%I
             FOR SELECT TO anon, authenticated
             USING (internal.can_read_pin(pin_id))',
            t
        );
    END LOOP;
END;
$$;

-- ============================================
-- 10. Travel journal
-- ============================================

GRANT SELECT ON public.travel_events
TO anon, authenticated;

CREATE POLICY travel_events_select
ON public.travel_events
FOR SELECT TO anon, authenticated
USING (internal.can_read_event(id));

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'event_tracks',
        'event_photos'
    ]
    LOOP
        EXECUTE format(
            'GRANT SELECT ON public.%I TO anon, authenticated',
            t
        );

        EXECUTE format(
            'CREATE POLICY event_children_select
             ON public.%I
             FOR SELECT TO anon, authenticated
             USING (internal.can_read_event(event_id))',
            t
        );
    END LOOP;
END;
$$;

-- ============================================
-- 11. Music journeys
-- ============================================

GRANT SELECT ON public.music_journeys
TO anon, authenticated;

GRANT SELECT ON public.journey_places
TO anon, authenticated;

CREATE POLICY journeys_select
ON public.music_journeys
FOR SELECT TO anon, authenticated
USING (internal.can_read_journey(id));

CREATE POLICY journey_places_select
ON public.journey_places
FOR SELECT TO anon, authenticated
USING (
    internal.can_read_journey(journey_id)
    AND (
        pin_id IS NULL
        OR internal.can_read_pin(pin_id)
    )
    AND (
        place_id IS NULL
        OR EXISTS (
            SELECT 1
            FROM public.places p
            WHERE p.id = place_id
        )
    )
);

-- ============================================
-- 12. User social collections
-- ============================================

GRANT SELECT, INSERT, DELETE ON
    public.playlist_likes,
    public.pin_likes,
    public.favorites,
    public.saved_playlists
TO authenticated;

CREATE POLICY playlist_likes_select
ON public.playlist_likes
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()));

CREATE POLICY playlist_likes_insert
ON public.playlist_likes
FOR INSERT TO authenticated
WITH CHECK (
    user_id = (SELECT auth.uid())
    AND internal.can_read_playlist(playlist_id)
);

CREATE POLICY playlist_likes_delete
ON public.playlist_likes
FOR DELETE TO authenticated
USING (user_id = (SELECT auth.uid()));

CREATE POLICY pin_likes_select
ON public.pin_likes
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()));

CREATE POLICY pin_likes_insert
ON public.pin_likes
FOR INSERT TO authenticated
WITH CHECK (
    user_id = (SELECT auth.uid())
    AND internal.can_read_pin(pin_id)
);

CREATE POLICY pin_likes_delete
ON public.pin_likes
FOR DELETE TO authenticated
USING (user_id = (SELECT auth.uid()));

CREATE POLICY favorites_select
ON public.favorites
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()));

CREATE POLICY favorites_insert
ON public.favorites
FOR INSERT TO authenticated
WITH CHECK (user_id = (SELECT auth.uid()));

CREATE POLICY favorites_delete
ON public.favorites
FOR DELETE TO authenticated
USING (user_id = (SELECT auth.uid()));

CREATE POLICY saved_playlists_select
ON public.saved_playlists
FOR SELECT TO authenticated
USING (user_id = (SELECT auth.uid()));

CREATE POLICY saved_playlists_insert
ON public.saved_playlists
FOR INSERT TO authenticated
WITH CHECK (
    user_id = (SELECT auth.uid())
    AND internal.can_read_playlist(playlist_id)
);

CREATE POLICY saved_playlists_delete
ON public.saved_playlists
FOR DELETE TO authenticated
USING (user_id = (SELECT auth.uid()));

-- ============================================
-- 13. Private tables remain inaccessible
-- ============================================

-- share_tokens:
-- No direct grants or policies for clients.
-- Access is mediated by protected server logic.

-- internal.api_quota_counters:
-- No client grants or RLS policies.

-- ============================================
-- 14. Private Storage bucket
-- ============================================

INSERT INTO storage.buckets (
    id,
    name,
    public,
    file_size_limit,
    allowed_mime_types
)
VALUES (
    'soundscape-media',
    'soundscape-media',
    false,
    10485760,
    ARRAY[
        'image/jpeg',
        'image/png',
        'image/webp'
    ]
);

-- Files are stored under:
-- users/<user-uuid>/<object-name>
-- editorial/<object-name>

CREATE POLICY soundscape_media_select
ON storage.objects
FOR SELECT TO authenticated
USING (
    bucket_id = 'soundscape-media'
    AND (
        (
            split_part(name, '/', 1) = 'users'
            AND split_part(name, '/', 2)
                = (SELECT auth.uid())::text
        )
        OR (
            split_part(name, '/', 1) = 'editorial'
            AND (SELECT internal.is_editor())
        )
    )
);

CREATE POLICY soundscape_media_insert
ON storage.objects
FOR INSERT TO authenticated
WITH CHECK (
    bucket_id = 'soundscape-media'
    AND (
        (
            split_part(name, '/', 1) = 'users'
            AND split_part(name, '/', 2)
                = (SELECT auth.uid())::text
            AND split_part(name, '/', 3) <> ''
        )
        OR (
            split_part(name, '/', 1) = 'editorial'
            AND (SELECT internal.is_editor())
        )
    )
);

CREATE POLICY soundscape_media_update
ON storage.objects
FOR UPDATE TO authenticated
USING (
    bucket_id = 'soundscape-media'
    AND (
        (
            split_part(name, '/', 1) = 'users'
            AND split_part(name, '/', 2)
                = (SELECT auth.uid())::text
        )
        OR (
            split_part(name, '/', 1) = 'editorial'
            AND (SELECT internal.is_editor())
        )
    )
)
WITH CHECK (
    bucket_id = 'soundscape-media'
    AND (
        (
            split_part(name, '/', 1) = 'users'
            AND split_part(name, '/', 2)
                = (SELECT auth.uid())::text
            AND split_part(name, '/', 3) <> ''
        )
        OR (
            split_part(name, '/', 1) = 'editorial'
            AND (SELECT internal.is_editor())
        )
    )
);

CREATE POLICY soundscape_media_delete
ON storage.objects
FOR DELETE TO authenticated
USING (
    bucket_id = 'soundscape-media'
    AND (
        (
            split_part(name, '/', 1) = 'users'
            AND split_part(name, '/', 2)
                = (SELECT auth.uid())::text
        )
        OR (
            split_part(name, '/', 1) = 'editorial'
            AND (SELECT internal.is_editor())
        )
    )
);
