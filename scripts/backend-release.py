#!/usr/bin/env python3
"""Local, immutable backend build/stage/activation. No networking except health.

Build runs on CPU under storage-build. Stage never restarts a service. Activation
requires an operator-owned config and a new durable operation ID, including for
rollback. Unknown activation is reconciled by pointer AND actual health only.
"""
import argparse, gzip, hashlib, io, json, os, pathlib, re, shutil, stat, subprocess, tarfile, time, urllib.request, uuid

LIMIT = 512 * 1024**2
HASH = re.compile(r'^[0-9a-f]{64}$')
def canonical(v): return json.dumps(v, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
def digest(b): return hashlib.sha256(b).hexdigest()
def syncdir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)
def atomic_json(path, value):
    path = pathlib.Path(path); tmp = path.with_name('.receipt-' + str(uuid.uuid4()))
    with open(tmp, 'xb') as f: os.chmod(tmp, 0o600); f.write(canonical(value) + b'\n'); f.flush(); os.fsync(f.fileno())
    os.replace(tmp, path); syncdir(path.parent)
def read_regular(path, maximum=LIMIT):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_size > maximum: raise ValueError('Not a bounded regular file')
        with os.fdopen(fd, 'rb', closefd=False) as f: data = f.read(maximum + 1)
        after = os.fstat(fd)
        if len(data) != before.st_size or (before.st_mtime_ns, before.st_ctime_ns) != (after.st_mtime_ns, after.st_ctime_ns): raise ValueError('File changed')
        return data
    finally: os.close(fd)
def manifest_file(path):
    m = json.loads(read_regular(path, 16384))
    if set(m) != {'schema','sha256','size','version','sourceCommit','releaseDigest'} or m['schema'] != 'nextcomp-backend-artifact/v1' or not HASH.fullmatch(m['sha256']) or not HASH.fullmatch(m['releaseDigest']) or type(m['size']) is not int or not 0 < m['size'] <= LIMIT: raise ValueError('Invalid manifest')
    return m
def safe_name(name):
    return isinstance(name, str) and len(name) < 512 and not name.startswith('/') and '\\' not in name and '\x00' not in name and all(p not in ('', '.', '..') for p in name.split('/'))
def build(source, output):
    source = pathlib.Path(source).resolve(); output = pathlib.Path(output).resolve()
    if not output.is_dir() or source == output or source in output.parents: raise ValueError('Build output must be separate')
    git = ['git', '-c', 'safe.directory=' + str(source), '-C', str(source)]
    commit = subprocess.check_output(git + ['rev-parse','HEAD'], text=True).strip()
    tracked = subprocess.check_output(git + ['ls-tree','-r','--name-only','HEAD'], text=True).splitlines()
    allowed = lambda p: p in ('package.json','package-lock.json') or p.startswith(('server/','web/','scripts/'))
    names = [p for p in tracked if allowed(p)]
    dirty = subprocess.check_output(git + ['diff','--name-only','HEAD'], text=True).splitlines()
    if any(allowed(p) for p in dirty): raise ValueError('Commit runtime source before building')
    deps = source / 'node_modules'
    for parent, dirs, files in os.walk(deps, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d != '.bin')
        if any((pathlib.Path(parent)/d).is_symlink() for d in dirs): raise ValueError('Dependency directory link')
        names.extend(str((pathlib.Path(parent)/f).relative_to(source)) for f in sorted(files))
    if not deps.is_dir() or len(names) > 19999: raise ValueError('Missing or oversized dependency closure')
    records = {}; total = 0
    def payload(name):
        if not safe_name(name): raise ValueError('Unsafe source name')
        path = source / name; resolved = path.resolve()
        if source not in resolved.parents: raise ValueError('Dependency link escapes source')
        raw = read_regular(resolved); mode = 0o755 if resolved.stat().st_mode & 0o111 else 0o644
        return raw, mode
    for name in sorted(names):
        raw, mode = payload(name); total += len(raw)
        if total > LIMIT: raise ValueError('Release exceeds unpacked limit')
        records[name] = {'sha256':digest(raw),'size':len(raw),'mode':mode}
    version = json.loads(read_regular(source/'package.json',65536))['version']
    release = {'schema':'nextcomp-backend-release/v1','sourceCommit':commit,'version':version,'files':records}
    release['releaseDigest'] = digest(canonical(release))
    temp = output / ('.build-' + str(uuid.uuid4()))
    with open(temp, 'xb') as f:
        with gzip.GzipFile(fileobj=f, mode='wb', filename='', mtime=0) as z:
            with tarfile.open(fileobj=z, mode='w', format=tarfile.PAX_FORMAT) as t:
                for name in sorted(records):
                    raw, mode = payload(name)
                    if digest(raw) != records[name]['sha256'] or len(raw) != records[name]['size'] or mode != records[name]['mode']: raise ValueError('Source changed during build')
                    info=tarfile.TarInfo(name); info.size=len(raw); info.mode=mode; info.mtime=0; t.addfile(info,io.BytesIO(raw))
                raw=canonical(release)+b'\n'; info=tarfile.TarInfo('backend-release.json'); info.size=len(raw); info.mode=0o644; info.mtime=0; t.addfile(info,io.BytesIO(raw))
        f.flush(); os.fsync(f.fileno())
    raw=read_regular(temp); sha=digest(raw)
    if len(raw)>LIMIT: raise ValueError('Archive exceeds transport limit')
    target=output/(sha+'.tar.gz')
    try: os.link(temp,target)
    except FileExistsError:
        if digest(read_regular(target)) != sha: raise ValueError('Existing archive corrupt')
    temp.unlink(); syncdir(output)
    m={'schema':'nextcomp-backend-artifact/v1','sha256':sha,'size':len(raw),'version':version,'sourceCommit':commit,'releaseDigest':release['releaseDigest']}
    mp=output/(sha+'.json')
    if mp.exists():
        if json.loads(read_regular(mp,16384)) != m: raise ValueError('Existing manifest differs')
    else: atomic_json(mp,m)
    return m
def release_metadata(root):
    r=json.loads(read_regular(pathlib.Path(root)/'backend-release.json',8*1024**2)); value=dict(r); expected=value.pop('releaseDigest',None)
    if r.get('schema')!='nextcomp-backend-release/v1' or not HASH.fullmatch(expected or '') or digest(canonical(value))!=expected or not isinstance(r.get('files'),dict): raise ValueError('Invalid release metadata')
    return r
def verify_release(root, expected):
    root=pathlib.Path(root)
    if root.is_symlink() or not root.is_dir(): raise ValueError('Staged root is not a physical directory')
    r=release_metadata(root)
    if r['releaseDigest']!=expected: raise ValueError('Wrong release')
    actual=set()
    for parent, dirs, files in os.walk(root,followlinks=False):
        if any((pathlib.Path(parent)/d).is_symlink() for d in dirs): raise ValueError('Staged directory link')
        actual.update(str((pathlib.Path(parent)/f).relative_to(root)) for f in files)
    if actual != set(r['files'])|{'backend-release.json'}: raise ValueError('Staged release has unexpected files')
    for name, v in r['files'].items():
        if not safe_name(name) or set(v)!={'sha256','size','mode'} or v['mode'] not in (0o644,0o755): raise ValueError('Invalid payload record')
        path=root/name; raw=read_regular(path)
        if len(raw)!=v['size'] or digest(raw)!=v['sha256'] or stat.S_IMODE(path.stat().st_mode)!=v['mode']: raise ValueError('Staged payload differs')
    return r
def stage(archive, manifest, root):
    m=manifest_file(manifest); raw=read_regular(archive)
    if len(raw)!=m['size'] or digest(raw)!=m['sha256']: raise ValueError('Wrong archive digest/size')
    root=pathlib.Path(root).resolve(); releases=root/'releases'
    if not releases.is_dir() or releases.is_symlink(): raise ValueError('Operator release root missing')
    target=releases/m['releaseDigest']
    if target.exists(): verify_release(target,m['releaseDigest']); return {'state':'staged','releaseDigest':m['releaseDigest'],'sha256':m['sha256'],'reconciled':True}
    temp=releases/('.stage-'+str(uuid.uuid4())); entries=[]; seen=set(); size=0
    try:
        # Bound decompression before tarfile parses even a malicious PAX header.
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as z: expanded=z.read(LIMIT+32*1024**2+1)
        if len(expanded)>LIMIT+32*1024**2: raise ValueError('Expanded archive exceeds limit')
        with tarfile.open(fileobj=io.BytesIO(expanded),mode='r:') as t:
            for e in t:
                # No links, devices, FIFO, directory tricks, duplicates or extraction APIs.
                if len(entries)>=20000 or not safe_name(e.name) or e.name in seen or not e.isfile() or e.mode not in (0o644,0o755) or e.size<0: raise ValueError('Unsafe archive entry')
                size+=e.size
                if size>LIMIT: raise ValueError('Unpacked release too large')
                seen.add(e.name); entries.append(e)
            if 'backend-release.json' not in seen: raise ValueError('Missing release metadata')
            if any('/'.join(name.split('/')[:i]) in seen for name in seen for i in range(1,len(name.split('/')))): raise ValueError('Archive file/directory collision')
            # All archive headers pass before writing any payload.
            temp.mkdir(mode=0o700)
            for e in entries:
                dest=temp/e.name; dest.parent.mkdir(parents=True,exist_ok=True)
                fd=os.open(dest,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,e.mode)
                with os.fdopen(fd,'wb') as f, t.extractfile(e) as src:
                    shutil.copyfileobj(src,f,1024*1024); f.flush(); os.fchmod(f.fileno(),e.mode); os.fsync(f.fileno())
        r=verify_release(temp,m['releaseDigest'])
        if r['version']!=m['version'] or r['sourceCommit']!=m['sourceCommit']: raise ValueError('Manifest/release mismatch')
        for parent,dirs,_ in os.walk(temp): os.chmod(parent,0o755); syncdir(parent)
        os.rename(temp,target); syncdir(releases)
        return {'state':'staged','releaseDigest':m['releaseDigest'],'sha256':m['sha256'],'reconciled':False}
    finally:
        if temp.exists(): shutil.rmtree(temp)
def protected_config(path):
    st=os.lstat(path)
    if st.st_uid!=0 or st.st_mode&0o022: raise ValueError('Activation config must be operator-owned')
    c=json.loads(read_regular(path,16384))
    if c['unit'] not in ('nextcomp-federation-development.service','nextcomp-federation-development-gpu.service') or not pathlib.Path(c['root']).is_absolute() or c['health']!='http://127.0.0.1:19427/health': raise ValueError('Not an enrolled development activation')
    return c
def activate(c, operation, target_digest, expected_previous, action='activate', restart=None, health=None):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',operation) or not HASH.fullmatch(target_digest) or expected_previous!='absent' and not HASH.fullmatch(expected_previous): raise ValueError('Invalid immutable activation intent')
    root=pathlib.Path(c['root']).resolve(); receipts=root/'receipts'; current=root/'current'; target=root/'releases'/target_digest
    verify_release(target,target_digest)
    def pointer():
        if not current.exists() and not current.is_symlink(): return 'absent'
        if not current.is_symlink(): raise ValueError('Current release is not a managed pointer')
        resolved=current.resolve()
        if resolved.parent!=root/'releases': raise ValueError('Current release escapes enrolled root')
        return release_metadata(resolved)['releaseDigest']
    def actual_health():
        if health: return health()
        with urllib.request.urlopen(c['health'],timeout=5) as r: return json.loads(r.read(16384))
    def ready():
        try:
            h=actual_health(); return h.get('ok') is True and h.get('releaseDigest')==target_digest
        except Exception: return False
    path=receipts/(operation+'.json'); lock=receipts/(operation+'.lock'); intent={'operationId':operation,'action':action,'previous':expected_previous,'target':target_digest,'unit':c['unit']}
    lockfd=os.open(lock,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600); os.close(lockfd)
    try:
        old=json.loads(read_regular(path,16384)) if path.exists() else None
        if old:
            if any(old.get(k)!=v for k,v in intent.items()): raise ValueError('Operation ID intent differs')
            if pointer()==target_digest and ready():
                result={**intent,'state':'active','reconciled':True}; atomic_json(path,result); return result
            raise ValueError('Prior activation is held; do not replay its restart')
        if pointer()!=expected_previous: raise ValueError('Previous release changed')
        atomic_json(path,{**intent,'state':'activating'})
        link=root/('.current-'+str(uuid.uuid4())); os.symlink(target,link); os.replace(link,current); syncdir(root)
        # Receipt precedes the only service-control effect. A lost response never replays it.
        if restart: restart()
        else: subprocess.run(['systemctl','restart',c['unit']],check=True,timeout=30,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(20):
            if ready():
                result={**intent,'state':'active','reconciled':False}; atomic_json(path,result); return result
            time.sleep(.25)
        atomic_json(path,{**intent,'state':'held'}); raise ValueError('Readiness not proven; explicit rollback uses a new operation ID')
    finally: lock.unlink()
def main():
    p=argparse.ArgumentParser(); sub=p.add_subparsers(dest='command',required=True)
    b=sub.add_parser('build'); b.add_argument('--source',required=True); b.add_argument('--output',required=True)
    s=sub.add_parser('stage'); s.add_argument('--archive',required=True); s.add_argument('--manifest',required=True); s.add_argument('--root',required=True)
    a=sub.add_parser('activate'); a.add_argument('--config',required=True); a.add_argument('--operation',required=True); a.add_argument('--release-digest',required=True); a.add_argument('--expected-previous',required=True); a.add_argument('--rollback',action='store_true')
    v=p.parse_args()
    if v.command=='build': result=build(v.source,v.output)
    elif v.command=='stage': result=stage(v.archive,v.manifest,v.root)
    else: result=activate(protected_config(v.config),v.operation,v.release_digest,v.expected_previous,'rollback' if v.rollback else 'activate')
    print(json.dumps(result,sort_keys=True))
if __name__=='__main__':
    try: main()
    except Exception: print('Local release operation held; inspect its durable receipt. No automatic retry or rollback.'); raise SystemExit(1)
