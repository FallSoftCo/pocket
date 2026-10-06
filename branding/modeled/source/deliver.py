import socket,subprocess,sys
assert socket.gethostname()=='ozzz-aosp'
for script in ['package_objects.py','finish_delivery.py']:subprocess.run([sys.executable,script],check=True)
