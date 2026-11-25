-- A durable deletion job remains actionable after the initiating session is revoked.
ALTER TABLE internal.account_deletions ADD COLUMN maintenance_attempted_at timestamptz;
CREATE INDEX account_deletions_maintenance_idx
  ON internal.account_deletions (maintenance_attempted_at ASC NULLS FIRST, user_id);

CREATE FUNCTION public.edge_claim_account_deletion()
RETURNS uuid LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  UPDATE internal.account_deletions d SET maintenance_attempted_at = clock_timestamp()
  WHERE d.user_id = (
    SELECT q.user_id FROM internal.account_deletions q
    WHERE q.maintenance_attempted_at IS NULL OR q.maintenance_attempted_at < now() - interval '1 minute'
    ORDER BY q.maintenance_attempted_at NULLS FIRST, q.user_id
    LIMIT 1 FOR UPDATE SKIP LOCKED
  )
  RETURNING d.user_id;
$$;
REVOKE ALL ON FUNCTION public.edge_claim_account_deletion() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.edge_claim_account_deletion() TO service_role;

COMMENT ON TABLE internal.mutation_receipts IS
  'Retain successful mutation receipts for the lifetime of the owning account. A future expiry policy requires an offline replay contract.';

NOTIFY pgrst, 'reload schema';
