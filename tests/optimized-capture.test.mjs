import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';

test('optimized UI observation cannot accept a retained hierarchy after a failed fresh read',()=>{
 const result=spawnSync('python3',['-c',String.raw`
import importlib.util,pathlib,tempfile
spec=importlib.util.spec_from_file_location('qa','scripts/qualify-native-optimized.py')
qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
with tempfile.TemporaryDirectory(prefix='nextcomp-capture-') as directory:
 q=pathlib.Path(directory)
 stale=b'<hierarchy><node text="old answer"/></hierarchy>'
 fresh=b'<hierarchy><node text="current answer"/></hierarchy>'
 (q/'last-visible-hierarchy.xml').write_bytes(stale)
 calls=[];responses=iter([b'ERROR: null root node returned by UiTestAutomationBridge.',fresh+b'UI hierchary dumped to: /proc/self/fd/1\n'])
 def adb(*args):
  calls.append(args)
  assert args==('exec-out','uiautomator','dump','/proc/self/fd/1'),'No retained-file read or input is permitted'
  return next(responses)
 assert qa.read_visible_hierarchy(adb,q)==fresh
 assert len(calls)==2 and len(list(q.glob('hierarchy-capture-*.raw')))==1
 assert (q/'last-visible-hierarchy.xml').read_bytes()==fresh
 calls.clear()
 def failed(*args):
  calls.append(args)
  assert args==('exec-out','uiautomator','dump','/proc/self/fd/1')
  return b'ERROR: null root node returned by UiTestAutomationBridge.'
 try:qa.read_visible_hierarchy(failed,q)
 except RuntimeError as error:assert 'no valid current hierarchy' in str(error)
 else:raise AssertionError('A previous successful observation must not hide failed current reads')
 assert len(calls)==3 and len(list(q.glob('hierarchy-capture-*.raw')))==4
 assert (q/'last-visible-hierarchy.xml').read_bytes()==fresh
`],{cwd:fileURLToPath(new URL('..',import.meta.url)),encoding:'utf8',timeout:10000});
 assert.equal(result.status,0,result.stderr||result.stdout);
});
