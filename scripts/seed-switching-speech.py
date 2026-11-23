#!/usr/bin/env python3
"""Prepare fixture-only preference copies; never accesses a device or production profile.

Back up both preference files and stop only the owned fixture app before installing
these generated copies. Restore both backups after native qualification.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('pocket', type=Path, help='owned emulator pocket.xml backup')
parser.add_argument('output', type=Path, help='task-owned output directory')
parser.add_argument('--speech', type=Path, help='optional speech-playback.xml backup')
args = parser.parse_args()
pocket = ET.parse(args.pocket)
root = pocket.getroot()
values = {node.get('name'): node.get('value', node.text or '') for node in root}
local = values.get('activeLocal', 'false') == 'true'
prefix = 'local:' if local else ''
base, token = values.get(prefix+'server', ''), values.get(prefix+'token', '')
if token != 'synthetic-lifecycle-only' or not base.startswith('http://127.0.0.1:'):
    parser.error('Only the loopback synthetic lifecycle fixture is supported; pair first.')

def put(tree, name, value):
    for node in list(tree):
        if node.get('name') == name:
            tree.remove(node)
    ET.SubElement(tree, 'string', name=name).text = value

identity = hashlib.sha256((base+'\0'+token).encode()).hexdigest()
put(root, 'notification-thread:'+identity+':4242', 'lifecycle-18')
profile = hashlib.sha256((str(local).lower()+':'+base+':'+token).encode()).hexdigest()
speech = ET.parse(args.speech) if args.speech else ET.ElementTree(ET.Element('map'))
queue = json.dumps({'messages':[{'id':4242,'title':'Foreign saved session 18 update',
    'kind':'message','text':'FOREIGN SAVED SPEECH belongs only to lifecycle session 18.',
    'needsFetch':False}], 'chunk':0, 'position':0}, separators=(',', ':'))
put(speech.getroot(), 'queue', queue)
put(speech.getroot(), 'queueProfile', profile)
put(speech.getroot(), 'queue:'+profile, queue)
args.output.mkdir(parents=True, exist_ok=True)
pocket.write(args.output/'pocket.xml', encoding='utf-8', xml_declaration=True)
speech.write(args.output/'speech-playback.xml', encoding='utf-8', xml_declaration=True)
print('Prepared synthetic preferences with paused foreign speech for session 18. No device changed.')
