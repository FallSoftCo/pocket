import subprocess,socket
assert socket.gethostname()=='ozzz-aosp'
for script in ['build_objects.py','build_spins.py','build_studies.py','build_mark_motion.py']:
 subprocess.run(['/opt/hfs22.0.429/bin/hython',script],check=True)
