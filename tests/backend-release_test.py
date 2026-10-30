import importlib.util, io, json, pathlib, tarfile, tempfile, unittest
spec=importlib.util.spec_from_file_location('release',pathlib.Path(__file__).resolve().parents[1]/'scripts/backend-release.py')
r=importlib.util.module_from_spec(spec); spec.loader.exec_module(r)
class ReleaseJourney(unittest.TestCase):
 def test_stage_unsafe_entries_lost_activation_receipt_and_rollback(self):
  with tempfile.TemporaryDirectory(prefix='nextcomp-release-') as d:
   root=pathlib.Path(d); (root/'releases').mkdir(); (root/'receipts').mkdir()
   def archive(payload, extra=None):
    files={'server/index.mjs':{'sha256':r.digest(payload),'size':len(payload),'mode':0o644}}
    release={'schema':'nextcomp-backend-release/v1','version':'fixture','sourceCommit':'a'*40,'files':files}; release['releaseDigest']=r.digest(r.canonical(release))
    path=root/(release['releaseDigest']+'.tar.gz')
    with tarfile.open(path,'w:gz') as t:
     for name,raw in [('server/index.mjs',payload),('backend-release.json',r.canonical(release))]:
      e=tarfile.TarInfo(name); e.mode=0o644; e.size=len(raw); t.addfile(e,io.BytesIO(raw))
     if extra: t.addfile(extra)
    raw=path.read_bytes(); manifest={'schema':'nextcomp-backend-artifact/v1','sha256':r.digest(raw),'size':len(raw),'version':'fixture','sourceCommit':'a'*40,'releaseDigest':release['releaseDigest']}
    mp=root/(release['releaseDigest']+'.json'); mp.write_text(json.dumps(manifest)); return path,mp,release['releaseDigest']
   first,mp,one=archive(b'first'); self.assertEqual(r.stage(first,mp,root)['state'],'staged'); self.assertTrue(r.stage(first,mp,root)['reconciled'])
   second,mp,two=archive(b'second'); r.stage(second,mp,root)
   for name,kind in [('../outside',tarfile.REGTYPE),('/absolute',tarfile.REGTYPE),('server/index.mjs',tarfile.REGTYPE),('link',tarfile.SYMTYPE),('hard',tarfile.LNKTYPE),('fifo',tarfile.FIFOTYPE)]:
    e=tarfile.TarInfo(name); e.type=kind; e.linkname='/etc/passwd'; e.mode=0o644
    bad,bm,_=archive(b'bad-'+name.encode(),e)
    with self.assertRaises(ValueError): r.stage(bad,bm,root)
   self.assertFalse((root.parent/'outside').exists())
   calls=[]; c={'root':str(root),'unit':'nextcomp-federation-development.service','health':'http://127.0.0.1:19427/health'}
   def health(): return {'ok':True,'releaseDigest':r.release_metadata((root/'current').resolve())['releaseDigest']}
   def lost(): calls.append('restart'); raise RuntimeError('restart returned no receipt')
   with self.assertRaises(RuntimeError): r.activate(c,'first',one,'absent',restart=lost,health=health)
   result=r.activate(c,'first',one,'absent',restart=lambda:calls.append('duplicate'),health=health)
   self.assertEqual(calls,['restart']); self.assertTrue(result['reconciled'])
   self.assertEqual(r.activate(c,'second',two,one,restart=lambda:calls.append('second'),health=health)['state'],'active')
   self.assertEqual(r.activate(c,'rollback',one,two,'rollback',restart=lambda:calls.append('rollback'),health=health)['state'],'active')
   self.assertEqual(calls,['restart','second','rollback'])
   with self.assertRaises(ValueError): r.activate(c,'second',two,one,restart=lambda:calls.append('bad'),health=lambda:{'ok':False})
   self.assertEqual(calls,['restart','second','rollback'])
if __name__=='__main__': unittest.main()
