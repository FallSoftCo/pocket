import json,sys,hashlib,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();source=root/'render'/sys.argv[1];target=Path(sys.argv[2]);receipt_path=source/('source-collection.json' if (source/'source-collection.json').exists() else 'sequence-complete.json');receipt=json.loads(receipt_path.read_text());converter=(root/'display_exr.py').read_text();exposure='1' if sys.argv[1]=='mark' else '.85'
for frame in range(receipt['frames'][0],receipt['frames'][1]+1):
 p=source/('frame.%06d.exr'%frame);assert hashlib.sha256(p.read_bytes()).hexdigest()==receipt['files'][p.name]['sha256']
 out=target/('frame.%06d.png'%frame)
 if not out.exists():
  sys.argv=['display_exr.py',str(p),str(out),'--exposure',exposure];exec(compile(converter,'display_exr.py','exec'),{'__name__':'__main__'})
 print('DISPLAY FRAME',frame,flush=True)
