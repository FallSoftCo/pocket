import subprocess,socket
from pathlib import Path
assert socket.gethostname()=='ozzz-aosp'
b=Path('/aosp/houdini/nextcomp');d=b/'voxel-v03/delivery';r=b/'roles-v01/delivery'
for source,target,animated in [(d/'native-spin-review.webp',d/'native-spin-review.mp4',True),(d/'symbols-1920.png',d/'symbols-review.mp4',False),(r/'roles-1920.png',r/'roles-review.mp4',False)]:
 cmd=['ffmpeg','-y','-threads','2']
 if animated:cmd+=['-i',str(source)]
 else:cmd+=['-loop','1','-i',str(source),'-t','3']
 # Read numbered native-derived frames directly for ffmpeg webp compatibility.
 if animated:cmd=['ffmpeg','-y','-threads','2','-framerate','6','-i',str(d/'frames-1920/frame-%02d.png')]
 cmd+=['-vf','scale=512:512,format=yuv420p','-filter_threads','2','-c:v','libx264','-threads','2','-crf','18','-movflags','+faststart',str(target)]
 subprocess.run(cmd,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
print('GALLERY PREVIEWS ENCODED')
