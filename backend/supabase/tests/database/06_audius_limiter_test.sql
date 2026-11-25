BEGIN;
CREATE EXTENSION IF NOT EXISTS pgtap WITH SCHEMA extensions;
SET LOCAL search_path=public,extensions,pg_catalog;
SELECT plan(12);

DELETE FROM internal.audius_budget;
SELECT is(public.edge_audius_reserve()->>'configured','false','missing configuration fails closed');
INSERT INTO internal.audius_budget(requests_per_second,requests_per_month) VALUES(1,2);
SELECT is(public.edge_audius_reserve()->>'allowed','true','first operation reserves a request');
SELECT is(public.edge_audius_reserve()->>'allowed','false','sliding second limit rejects second call');
UPDATE internal.audius_budget SET recent=ARRAY[clock_timestamp()-interval '2 seconds'];
SELECT is(public.edge_audius_reserve()->>'allowed','true','expired window permits next request');
UPDATE internal.audius_budget SET recent='{}';
SELECT is(public.edge_audius_reserve()->>'allowed','false','month budget persists after second window');
UPDATE internal.audius_budget SET month_start=month_start-interval '1 month';
SELECT is(public.edge_audius_reserve()->>'allowed','true','UTC month rollover resets usage');
SELECT public.edge_audius_reserve(12);
UPDATE internal.audius_budget SET recent='{}';
SELECT ok((public.edge_audius_reserve()->>'retryAfter')::integer BETWEEN 1 AND 12,'provider cooldown applies to all requests');
SELECT ok(public.edge_audius_lease('ProbeFixture','00000000-0000-4000-8000-000000000001'),'first probe takes lease');
SELECT ok(NOT public.edge_audius_lease('ProbeFixture','00000000-0000-4000-8000-000000000002'),'another token cannot acquire live lease');
UPDATE internal.audius_probe_leases SET until_at=clock_timestamp()-interval '1 second' WHERE provider_id='ProbeFixture';
SELECT ok(public.edge_audius_lease('ProbeFixture','00000000-0000-4000-8000-000000000002'),'expired lease can be replaced');
SELECT ok(NOT public.edge_audius_lease('ProbeFixture','00000000-0000-4000-8000-000000000001',true),'old token cannot release successor');
SELECT ok(NOT has_function_privilege('anon','public.edge_audius_reserve(integer)','EXECUTE')
  AND NOT has_function_privilege('authenticated','public.edge_audius_lease(text,uuid,boolean)','EXECUTE')
  AND NOT has_table_privilege('service_role','internal.audius_budget','INSERT')
  AND NOT has_table_privilege('authenticated','internal.audius_probe_leases','SELECT'),'budget and lease privileges are restricted');
SELECT * FROM finish();
ROLLBACK;
