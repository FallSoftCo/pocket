"""OZZZ-OS: compare exact native UI captures; never synthesize product UI."""
import socket,subprocess,json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
assert socket.gethostname()=='ozzz-aosp'
root=Path.cwd();src=root/'native-ui';d=root/'delivery';font=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',26)
names=['conversation-normal.png','conversation-font2.png'];canvas=Image.new('RGB',(2160,2450),(14,22,30));draw=ImageDraw.Draw(canvas)
for i,name in enumerate(names):
 im=Image.open(src/name).convert('RGB');assert im.size==(1080,2400);canvas.paste(im,(i*1080,50));draw.text((i*1080+16,10),'Native Android emulator / '+('normal text' if i==0 else '2x text'),font=font,fill=(221,232,241))
canvas.save(d/'native-ui-normal-large.png')
subprocess.run(['ffmpeg','-y','-threads','2','-loop','1','-i',str(d/'native-ui-normal-large.png'),'-t','3','-vf','format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(d/'native-ui-normal-large.mp4')],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print('Native UI evidence packaged; screenshots are exact native pixels, not phone frame-delivery proof',flush=True)
