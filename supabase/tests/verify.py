"""Run against an EMPTY local PostgreSQL database, never a live Supabase project.
PGHOST/PGPORT/PGUSER select the temporary instance. Requires psql on PATH.
"""
import concurrent.futures
import datetime
import json
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
USERS = [f'00000000-0000-0000-0000-{n:012d}' for n in (1,2,3)]
count = 0

def sql(source, user=None, role=None, error=None):
    prefix = ''
    if user: prefix += f"set request.jwt.claim.sub = '{user}';"
    if role: prefix += f'set role {role};'
    proc = subprocess.run(['psql','-X','-q','-A','-t','-v','ON_ERROR_STOP=1'],input=prefix+source,text=True,capture_output=True)
    if error:
        assert proc.returncode != 0 and error in proc.stderr, (proc.stdout,proc.stderr)
        return None
    assert proc.returncode == 0, proc.stderr
    return proc.stdout.strip()

def rpc(action, workspace=None, payload=None, user=USERS[0], error=None):
    quote = lambda x: "'"+str(x).replace("'","''")+"'"
    result = sql(f"select public.daymark_rpc({quote(action)}, {quote(workspace)+'::uuid' if workspace else 'null'}, {quote(json.dumps(payload or {}))}::jsonb);",user,'authenticated',error)
    return json.loads(result) if result is not None else None

def ok(name):
    global count
    count += 1
    print(f'PASS {name}',flush=True)

sql((ROOT/'tests/bootstrap.sql').read_text())
sql((ROOT/'migrations/202611040001_daymark.sql').read_text())
ws = rpc('create',payload={'id':str(uuid.uuid4()),'name':'Together','time_zone':'UTC'})['id']
assert len(rpc('list')) == 1
assert rpc('list',user=USERS[1]) == []
ok('workspace membership isolates list discovery')
rpc('snapshot',ws,user=USERS[1],error='no longer have access')
sql("select public.daymark_rpc('list');",role='anon',error='permission denied')
sql('select * from daymark.tasks;',user=USERS[0],role='authenticated',error='permission denied')
ok('nonmembers, anonymous users, and direct table access are rejected')
invite=rpc('invite',ws)['code']
rpc('join',payload={'code':invite},user=USERS[1])
rpc('join',payload={'code':invite},user=USERS[1])
assert len(rpc('invite',ws)['members'])==2
rpc('invite',ws,user=USERS[1],error='Only the owner')
ok('invites join idempotently; only owner manages members')
today=datetime.datetime.now(datetime.timezone.utc).date().isoformat()
request={'request_id':str(uuid.uuid4()),'root_id':str(uuid.uuid4()),'title':'Shared task','priority':'P1','day':today}
rpc('add_task',ws,request)
rpc('add_task',ws,request)
tasks=rpc('snapshot',ws,user=USERS[1])['tasks']
assert len(tasks)==1
task=tasks[0]
rpc('update_task',ws,{**task,'title':'Member edit','request_id':str(uuid.uuid4())},user=USERS[1])
rpc('update_task',ws,{**task,'title':'Stale overwrite','request_id':str(uuid.uuid4())},error='This task changed')
rpc('delete_task',ws,{'id':task['id'],'request_id':str(uuid.uuid4())},error='This task changed')
assert rpc('snapshot',ws)['tasks'][0]['title']=='Member edit'
ok('two users edit shared data; stale or missing revisions fail; retries do not duplicate')
# Seed an overdue task to exercise parallel rollover without depending on the clock.
sql(f"update daymark.tasks set day=(now() at time zone 'UTC')::date-3, origin_day=(now() at time zone 'UTC')::date-3 where workspace_id='{ws}';")
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
    list(pool.map(lambda _: rpc('snapshot',ws),range(5)))
tasks=rpc('snapshot',ws)['tasks']
assert len(tasks)==4 and sum(t['status']=='OPEN' for t in tasks)==1
assert sum(t['status']=='MOVED' for t in tasks)==3
ok('concurrent rollover creates exactly one snapshot per day')
# Device reminder fields are absent from the shared events table.
event={'request_id':str(uuid.uuid4()),'id':0,'title':'Meeting','start':today+'T15:30:00','repeat':'DAILY'}
rpc('save_event',ws,event)
e=rpc('snapshot',ws)['events'][0]
assert 'notified_24h' not in e and e['version']==1
rpc('save_event',ws,{**e,'title':'New title','request_id':str(uuid.uuid4())},user=USERS[1])
rpc('delete_event',ws,{**e,'request_id':str(uuid.uuid4())},error='This event changed')
rpc('save_event',ws,{**e,'title':'Bad revision','version':None,'request_id':str(uuid.uuid4())},error='This event changed')
assert rpc('snapshot',ws)['events'][0]['version']==2
ok('events reject stale edits; notification bookkeeping is not shared')
imported={'request_id':str(uuid.uuid4()),'tasks':[{'root_id':'legacy','title':'Old task','priority':'P0','status':'DONE','day':today,'origin_day':today,'moved_to':None}],
          'events':[{'title':'Old event','start':today+'T10:00:00','repeat':'ONCE'}]}
rpc('import',ws,imported)
rpc('import',ws,imported)
snap=rpc('snapshot',ws)
assert len(snap['tasks'])==5 and len(snap['events'])==2
broken={**imported,'request_id':str(uuid.uuid4()),'events':[{'title':'Invalid','start':'bad','repeat':'ONCE'}]}
rpc('import',ws,broken,error='invalid input syntax')
assert len(rpc('snapshot',ws)['tasks'])==5
ok('legacy import is idempotent and failures roll back the entire batch')
rotated=rpc('rotate_invite',ws)['code']
assert rotated!=invite
rpc('join',payload={'code':invite},user=USERS[2],error='Invitation is invalid')
rpc('remove_member',ws,{'user_id':USERS[1]})
rpc('snapshot',ws,user=USERS[1],error='no longer have access')
assert rpc('list',user=USERS[1])==[]
rpc('remove_member',ws,{'user_id':USERS[0]},error='owner cannot be removed')
ok('rotation invalidates invitations and member removal immediately revokes API access')
snap=rpc('snapshot',ws)
assert rpc('snapshot',ws,{'revision':snap['revision']})['unchanged'] is True
historical=next(t for t in snap['tasks'] if t['status']=='MOVED')
rpc('delete_task',ws,{**historical,'request_id':str(uuid.uuid4())})
changed=rpc('snapshot',ws,{'revision':snap['revision']})
assert 'tasks' in changed and changed['revision']>snap['revision']
assert all(t['id']!=historical['id'] for t in changed['tasks'])
ok('unchanged snapshots save bandwidth; history deletion increments the revision')
other = rpc('create',payload={'id':str(uuid.uuid4()),'name':'Other workspace','time_zone':'UTC'},user=USERS[2])['id']
rpc('add_task',other,{**request,'request_id':str(uuid.uuid4()),'root_id':str(uuid.uuid4())},user=USERS[2])
foreign = rpc('snapshot',other,user=USERS[2])['tasks'][0]
rpc('delete_task',ws,{**foreign,'request_id':str(uuid.uuid4())},error='This task changed')
assert rpc('snapshot',other,user=USERS[2])['tasks'][0]==foreign
ok('a member cannot edit a task belonging to another workspace')
current = next(t for t in rpc('snapshot',ws)['tasks'] if t['status']=='OPEN')
def edit_once(title):
    try:
        rpc('update_task',ws,{**current,'title':title,'request_id':str(uuid.uuid4())})
        return True
    except AssertionError as error:
        assert 'This task changed' in str(error), error
        return False
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    assert sum(pool.map(edit_once,['Concurrent A','Concurrent B']))==1
ok('exactly one concurrent edit succeeds for a given task version')
revision=rpc('snapshot',ws)['revision']
sql("select public.daymark_rpc(null,'"+ws+"','"+json.dumps({'request_id':str(uuid.uuid4())})+"'::jsonb);",USERS[0],'authenticated',error='Unknown action')
assert rpc('snapshot',ws)['revision']==revision
ok('invalid null actions fail without changing workspace revision')
print(f'{count} database integration checks passed.')
