-- Run once in the Supabase SQL editor. No paid services or scheduled jobs required.
-- Tables are private; the single API entry point checks auth.uid() and membership.
begin;
create schema daymark;
revoke all on schema daymark from public, anon, authenticated;
create table daymark.workspaces (
 id uuid primary key default gen_random_uuid(), name text not null check(length(trim(name)) between 1 and 100),
 owner_id uuid not null references auth.users(id), time_zone text not null, revision bigint not null default 0,
 invite_code uuid not null default gen_random_uuid() unique
);
create table daymark.members (
 workspace_id uuid not null references daymark.workspaces(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 primary key(workspace_id,user_id)
);
create table daymark.tasks (
 id bigint generated always as identity primary key,
 workspace_id uuid not null references daymark.workspaces(id) on delete cascade,
 root_id text not null, title text not null check(length(trim(title)) between 1 and 2000),
 priority text not null check(priority in ('P0','P1','P2')),
 status text not null check(status in ('OPEN','DONE','STALE','MOVED')),
 day date not null, origin_day date not null, moved_to date,
 sync_version bigint not null default 1
);
create index on daymark.tasks(workspace_id, day);
create unique index on daymark.tasks(workspace_id,root_id) where status='OPEN';
create table daymark.events (
 id bigint generated always as identity primary key,
 workspace_id uuid not null references daymark.workspaces(id) on delete cascade,
 title text not null check(length(trim(title)) between 1 and 2000), start timestamp not null,
 repeat text not null check(repeat in ('ONCE','DAILY','WEEKLY','MONTHLY','YEARLY')),
 version bigint not null default 1,
 check(start = date_trunc('minute', start))
);
create index on daymark.events(workspace_id,start);
-- Successful mutation IDs make retries safe after a response is lost.
create table daymark.requests (
 workspace_id uuid not null references daymark.workspaces(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 request_id uuid not null, created_at timestamptz not null default now(),
 primary key(workspace_id,user_id,request_id)
);
alter table daymark.workspaces enable row level security;
alter table daymark.members enable row level security;
alter table daymark.tasks enable row level security;
alter table daymark.events enable row level security;
alter table daymark.requests enable row level security;
revoke all on all tables in schema daymark from public,anon,authenticated;
revoke all on all sequences in schema daymark from public,anon,authenticated;

create function public.daymark_rpc(action text, workspace uuid default null, payload jsonb default '{}'::jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
 uid uuid := auth.uid();
 w daymark.workspaces%rowtype;
 t daymark.tasks%rowtype;
 e daymark.events%rowtype;
 d date; next_day date; item jsonb; new_root text;
 rid uuid; rolled boolean := false;
begin
 if uid is null then raise exception 'Sign in required' using errcode='42501'; end if;
 if action='list' then
  return coalesce((select jsonb_agg(jsonb_build_object('id',x.id,'name',x.name,'time_zone',x.time_zone,'owner_id',x.owner_id) order by x.name,x.id)
   from daymark.workspaces x join daymark.members m on m.workspace_id=x.id where m.user_id=uid),'[]'::jsonb);
 end if;
 if action='create' then
  if not exists(select 1 from pg_catalog.pg_timezone_names where name=payload->>'time_zone') then raise exception 'Invalid time zone'; end if;
  -- Client UUID makes workspace creation idempotent too.
  insert into daymark.workspaces(id,name,owner_id,time_zone) values((payload->>'id')::uuid,trim(payload->>'name'),uid,payload->>'time_zone')
   on conflict(id) do nothing;
  select * into w from daymark.workspaces where id=(payload->>'id')::uuid and owner_id=uid;
  if not found then raise exception 'Workspace unavailable' using errcode='42501'; end if;
  insert into daymark.members values(w.id,uid) on conflict do nothing;
  return jsonb_build_object('id',w.id,'name',w.name,'time_zone',w.time_zone,'owner_id',w.owner_id);
 end if;
 if action='join' then
  select * into w from daymark.workspaces where invite_code=(payload->>'code')::uuid for update;
  if not found then raise exception 'Invitation is invalid or was replaced'; end if;
  insert into daymark.members values(w.id,uid) on conflict do nothing;
  return jsonb_build_object('id',w.id,'name',w.name,'time_zone',w.time_zone,'owner_id',w.owner_id);
 end if;
 -- The workspace row serializes rollover, membership changes, and all edits.
 select * into w from daymark.workspaces where id=workspace for update;
 if not found or not exists(select 1 from daymark.members m where m.workspace_id=workspace and m.user_id=uid) then
  raise exception 'You no longer have access to this workspace' using errcode='42501';
 end if;
 if action in ('invite','rotate_invite','remove_member') then
  if w.owner_id<>uid then raise exception 'Only the owner can manage members' using errcode='42501'; end if;
  if action='rotate_invite' then update daymark.workspaces set invite_code=gen_random_uuid() where id=workspace returning * into w; end if;
  if action='remove_member' then
   if (payload->>'user_id')::uuid=uid then raise exception 'The owner cannot be removed'; end if;
   delete from daymark.members where workspace_id=workspace and user_id=(payload->>'user_id')::uuid;
  end if;
  return jsonb_build_object('code',w.invite_code,'members',coalesce((select jsonb_agg(user_id order by user_id) from daymark.members where workspace_id=workspace),'[]'::jsonb));
 end if;
 d := (now() at time zone w.time_zone)::date;
 -- One snapshot per missed day, exactly once across all devices.
 for t in select * from daymark.tasks where workspace_id=workspace and status='OPEN' and day<d order by id for update loop
  rolled := true;
  while t.day<d loop
   next_day := t.day+1;
   update daymark.tasks set status='MOVED', moved_to=next_day, sync_version=sync_version+1 where id=t.id;
   insert into daymark.tasks(workspace_id,root_id,title,priority,status,day,origin_day)
    values(workspace,t.root_id,t.title,t.priority,'OPEN',next_day,t.origin_day) returning * into t;
  end loop;
 end loop;
 if rolled then update daymark.workspaces set revision=revision+1 where id=workspace returning * into w; end if;
 if action='snapshot' then
  if w.revision is not distinct from (payload->>'revision')::bigint then
   return jsonb_build_object('unchanged',true,'revision',w.revision,'today',d);
  end if;
  return jsonb_build_object('today',d,'revision',w.revision,'tasks',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from daymark.tasks x where x.workspace_id=workspace),'[]'::jsonb),
   'events',coalesce((select jsonb_agg(to_jsonb(x) order by x.id) from daymark.events x where x.workspace_id=workspace),'[]'::jsonb));
 end if;
 if action is null or action not in ('add_task','update_task','delete_task','save_event','delete_event','import') then raise exception 'Unknown action'; end if;
 rid := (payload->>'request_id')::uuid;
 if rid is null then raise exception 'Request ID required'; end if;
 if exists(select 1 from daymark.requests where workspace_id=workspace and user_id=uid and request_id=rid) then return '{}'::jsonb; end if;
 if action='add_task' then
  insert into daymark.tasks(workspace_id,root_id,title,priority,status,day,origin_day)
   values(workspace,(payload->>'root_id')::uuid::text,trim(payload->>'title'),payload->>'priority','OPEN',(payload->>'day')::date,(payload->>'day')::date);
 elsif action in ('update_task','delete_task') then
  select * into t from daymark.tasks where workspace_id=workspace and id=(payload->>'id')::bigint;
  if not found or t.sync_version is distinct from (payload->>'sync_version')::bigint or (t.status='MOVED' and action='update_task') then
   raise exception 'This task changed. Close and reopen it before editing.' using errcode='40001';
  end if;
  if action='delete_task' then delete from daymark.tasks where id=t.id;
  else
   if payload->>'status' not in ('OPEN','DONE','STALE') then raise exception 'Invalid task status'; end if;
   update daymark.tasks set title=trim(payload->>'title'),priority=payload->>'priority',status=payload->>'status',sync_version=sync_version+1 where id=t.id;
  end if;
 elsif action in ('save_event','delete_event') then
  if coalesce((payload->>'id')::bigint,0)=0 and action='save_event' then
   insert into daymark.events(workspace_id,title,start,repeat) values(workspace,trim(payload->>'title'),(payload->>'start')::timestamp,payload->>'repeat');
  else
   select * into e from daymark.events where workspace_id=workspace and id=(payload->>'id')::bigint;
   if not found or e.version is distinct from (payload->>'version')::bigint then raise exception 'This event changed. Close and reopen it before editing.' using errcode='40001'; end if;
   if action='delete_event' then delete from daymark.events where id=e.id;
   else update daymark.events set title=trim(payload->>'title'),start=(payload->>'start')::timestamp,repeat=payload->>'repeat',version=version+1 where id=e.id; end if;
  end if;
 elsif action='import' then
  -- The client uses one stable request UUID per local database/workspace.
  for item in select value from jsonb_array_elements(payload->'tasks') loop
   -- Isolate roots from other phones' imports, while preserving all daily snapshots.
   new_root := uid::text || ':' || rid::text || ':' || (item->>'root_id');
   insert into daymark.tasks(workspace_id,root_id,title,priority,status,day,origin_day,moved_to)
    values(workspace,new_root,trim(item->>'title'),item->>'priority',item->>'status',(item->>'day')::date,(item->>'origin_day')::date,(item->>'moved_to')::date);
  end loop;
  for item in select value from jsonb_array_elements(payload->'events') loop
   insert into daymark.events(workspace_id,title,start,repeat) values(workspace,trim(item->>'title'),(item->>'start')::timestamp,item->>'repeat');
  end loop;
 end if;
 update daymark.workspaces set revision=revision+1 where id=workspace;
 insert into daymark.requests(workspace_id,user_id,request_id) values(workspace,uid,rid);
 return '{}'::jsonb;
end;
$$;
revoke all on function public.daymark_rpc(text,uuid,jsonb) from public,anon,authenticated;
grant execute on function public.daymark_rpc(text,uuid,jsonb) to authenticated;
commit;
