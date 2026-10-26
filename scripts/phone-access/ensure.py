#!/data/data/com.termux/files/usr/bin/python
import fcntl,json,os,pathlib,socket,subprocess,time
home=pathlib.Path.home();prefix=pathlib.Path(os.environ.get('PREFIX','/data/data/com.termux/files/usr'));state=home/'.local/state/phone-access';state.mkdir(parents=True,exist_ok=True);state.chmod(0o700)
lock=(state/'ensure.lock').open('a')
try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError:raise SystemExit(0)
now=int(time.time());result={'at':now,'action':'none','scope':'sshd-only','codexSpawned':False}
service=prefix/'var/service/sshd'
status=subprocess.run([str(prefix/'bin/sv'),'status',str(service)],capture_output=True,text=True,timeout=3)
result['service']=status.stdout.strip()[:250]
if (home/'.local/state/phone-access/disabled').exists() or (service/'down').exists():result['action']='disabled-respected'
elif status.returncode!=0:
    # A dead controller does not prove its listening child died. Never create a second daemon.
    child=0
    try:child=int((service/'supervise/pid').read_text().strip())
    except (OSError,ValueError):pass
    alive=False
    if child:
        try:os.kill(child,0);alive=True
        except ProcessLookupError:pass
    last=0
    try:last=int((state/'last-repair').read_text())
    except (OSError,ValueError):pass
    listening=False
    try:
        with socket.create_connection(('127.0.0.1',8022),timeout=1):listening=True
    except OSError:pass
    if alive or listening:result['action']='existing-child-needs-reconciliation'
    elif now-last<300:result['action']='repair-backoff'
    else:
        (state/'last-repair').write_text(str(now))
        logpath=state/'supervisor.log'
        if logpath.exists() and logpath.stat().st_size>32768:logpath.replace(state/'supervisor.previous.log')
        with logpath.open('ab') as log:
            subprocess.Popen([str(prefix/'bin/runsv'),str(service)],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True,close_fds=True)
        result['action']='started-one-ssh-supervisor'
        result['codexSpawned']=False
# A bounded snapshot, not an ever-growing event journal.
tmp=state/'health.next';tmp.write_text(json.dumps(result));tmp.replace(state/'health.json')
print(json.dumps(result))
