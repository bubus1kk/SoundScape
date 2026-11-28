ALTER TABLE internal.media_cleanup ADD COLUMN maintenance_attempted_at timestamptz;

CREATE INDEX media_cleanup_maintenance_idx
  ON internal.media_cleanup (maintenance_attempted_at ASC NULLS FIRST, path) WHERE NOT completed;

-- One account per scheduled invocation. The existing claim protects paths against reuse.
CREATE FUNCTION public.edge_claim_pending_media_cleanup()
RETURNS jsonb LANGUAGE plpgsql SET search_path = '' SECURITY DEFINER AS $$
DECLARE
  candidate internal.media_cleanup;
  paths jsonb;
BEGIN
  IF NOT pg_try_advisory_xact_lock(241510, 963421) THEN RETURN NULL; END IF;

  SELECT q.* INTO candidate FROM internal.media_cleanup q
  WHERE NOT q.completed
    AND (q.maintenance_attempted_at IS NULL OR q.maintenance_attempted_at < now() - interval '1 minute')
    AND internal.active_account(q.owner_id)
    AND NOT EXISTS (
      SELECT FROM internal.media_cleanup recent WHERE recent.owner_id = q.owner_id
        AND recent.maintenance_attempted_at >= now() - interval '1 minute'
    )
  ORDER BY q.maintenance_attempted_at ASC NULLS FIRST, q.path
  LIMIT 1 FOR UPDATE OF q SKIP LOCKED;
  IF NOT FOUND THEN RETURN NULL; END IF;

  paths := public.edge_claim_media_cleanup(candidate.owner_id);
  UPDATE internal.media_cleanup SET maintenance_attempted_at = clock_timestamp()
  WHERE path = candidate.path OR path IN (SELECT jsonb_array_elements_text(paths));
  RETURN jsonb_build_object('userId', candidate.owner_id, 'paths', paths);
END;
$$;

REVOKE ALL ON FUNCTION public.edge_claim_pending_media_cleanup() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_claim_pending_media_cleanup() TO service_role;

NOTIFY pgrst, 'reload schema';
