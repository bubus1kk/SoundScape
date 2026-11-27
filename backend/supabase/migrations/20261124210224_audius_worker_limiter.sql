-- Provision the singleton explicitly on the intended environment after budget approval.
CREATE TABLE internal.audius_budget (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  requests_per_second integer NOT NULL CHECK (requests_per_second BETWEEN 1 AND 100),
  requests_per_month integer NOT NULL CHECK (requests_per_month BETWEEN 1 AND 100000000),
  recent timestamptz[] NOT NULL DEFAULT '{}',
  month_start date,
  month_count integer NOT NULL DEFAULT 0,
  cooldown_until timestamptz NOT NULL DEFAULT '-infinity'
);
CREATE TABLE internal.audius_probe_leases (
  provider_id text PRIMARY KEY CHECK (provider_id ~ '^[A-Za-z0-9-]{1,120}$'),
  token uuid NOT NULL,
  until_at timestamptz NOT NULL
);
CREATE INDEX audius_probe_expiry_idx ON internal.audius_probe_leases(until_at);
ALTER TABLE internal.audius_budget ENABLE ROW LEVEL SECURITY;
ALTER TABLE internal.audius_probe_leases ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON internal.audius_budget, internal.audius_probe_leases FROM PUBLIC, anon, authenticated;
GRANT SELECT,UPDATE ON internal.audius_budget TO service_role;
GRANT SELECT,INSERT,UPDATE,DELETE ON internal.audius_probe_leases TO service_role;

CREATE FUNCTION public.edge_audius_reserve(p_cooldown_seconds integer DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path='' AS $$
DECLARE
  state internal.audius_budget;
  at_time timestamptz;
  month_key date;
  month_end timestamptz;
  retry integer;
BEGIN
  SELECT * INTO state FROM internal.audius_budget WHERE singleton FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('allowed',false,'configured',false); END IF;
  at_time := clock_timestamp();
  IF p_cooldown_seconds IS NOT NULL THEN
    UPDATE internal.audius_budget SET cooldown_until=greatest(cooldown_until,
      at_time+make_interval(secs=>least(greatest(p_cooldown_seconds,1),86400))) WHERE singleton;
    RETURN jsonb_build_object('allowed',false,'configured',true);
  END IF;
  IF state.cooldown_until>at_time THEN
    retry:=ceil(extract(epoch FROM state.cooldown_until-at_time));
  ELSE
    month_key:=date_trunc('month',at_time AT TIME ZONE 'UTC')::date;
    IF state.month_start IS DISTINCT FROM month_key THEN state.month_count:=0; END IF;
    SELECT coalesce(array_agg(t ORDER BY t),'{}') INTO state.recent
      FROM unnest(state.recent) t WHERE t>at_time-interval '1 second';
    IF state.month_count>=state.requests_per_month THEN
      month_end:=(month_key+interval '1 month') AT TIME ZONE 'UTC';
      retry:=ceil(extract(epoch FROM month_end-at_time));
    ELSIF cardinality(state.recent)>=state.requests_per_second THEN
      retry:=1;
    ELSE
      UPDATE internal.audius_budget SET recent=array_append(state.recent,at_time),
        month_start=month_key,month_count=state.month_count+1 WHERE singleton;
      RETURN jsonb_build_object('allowed',true,'configured',true);
    END IF;
  END IF;
  RETURN jsonb_build_object('allowed',false,'configured',true,'retryAfter',least(retry,86400));
END;
$$;

CREATE FUNCTION public.edge_audius_lease(p_provider_id text,p_token uuid,p_release boolean DEFAULT false)
RETURNS boolean LANGUAGE plpgsql VOLATILE SECURITY INVOKER SET search_path='' AS $$
DECLARE acquired boolean;
BEGIN
  IF p_release THEN
    DELETE FROM internal.audius_probe_leases WHERE provider_id=p_provider_id AND token=p_token;
    RETURN FOUND;
  END IF;
  DELETE FROM internal.audius_probe_leases WHERE provider_id IN (
    SELECT l.provider_id FROM internal.audius_probe_leases l
    WHERE l.until_at<=clock_timestamp() AND l.provider_id<>p_provider_id
    ORDER BY l.until_at LIMIT 16 FOR UPDATE SKIP LOCKED
  );
  INSERT INTO internal.audius_probe_leases AS l(provider_id,token,until_at)
    VALUES(p_provider_id,p_token,clock_timestamp()+interval '35 seconds')
  ON CONFLICT(provider_id) DO UPDATE SET token=EXCLUDED.token,until_at=clock_timestamp()+interval '35 seconds'
    WHERE l.until_at<=clock_timestamp()
  RETURNING true INTO acquired;
  RETURN coalesce(acquired,false);
END;
$$;
REVOKE ALL ON FUNCTION public.edge_audius_reserve(integer),public.edge_audius_lease(text,uuid,boolean)
  FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.edge_audius_reserve(integer),public.edge_audius_lease(text,uuid,boolean)
  TO service_role;
