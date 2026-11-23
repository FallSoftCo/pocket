#!/usr/bin/env python3
"""Small local review helper. Never prints UI text or pairing credentials."""
import re,subprocess,sys,xml.etree.ElementTree as ET
adb=['adb','-s',sys.argv[1]]
action=sys.argv[2]
if action=='tap':
 subprocess.run(adb+['shell','uiautomator','dump','/sdcard/pocket-review.xml'],check=True,stdout=subprocess.DEVNULL)
 root=ET.fromstring(subprocess.check_output(adb+['exec-out','cat','/sdcard/pocket-review.xml']))
 matches=[n for n in root.iter('node') if sys.argv[3] in (n.get('text'),n.get('content-desc'))]
 if not matches:raise SystemExit('Requested control not found')
 xy=list(map(int,re.findall(r'\d+',matches[0].get('bounds'))))
 subprocess.run(adb+['shell','input','tap',str((xy[0]+xy[2])//2),str((xy[1]+xy[3])//2)],check=True)
 print('Tapped requested control')
