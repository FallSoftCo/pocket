#!/usr/bin/env python3
"""Record/compare synthetic lifecycle UI bounds; never install or configure a device."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import urllib.request
import xml.etree.ElementTree as ET


def fixture_request(base, path, body=None):
    request = urllib.request.Request(base.rstrip('/') + path,
        data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json', 'x-fixture-key': 'synthetic-lifecycle-control'})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


parser = argparse.ArgumentParser(description=__doc__)
commands = parser.add_subparsers(dest='command', required=True)
action = commands.add_parser('action')
action.add_argument('action', choices=['reset','drain','recover','reconnect','partial','complete','background','preview','discover','remove'])
action.add_argument('--fixture', default='http://127.0.0.1:19987')
action.add_argument('--id')
snapshot = commands.add_parser('snapshot')
snapshot.add_argument('--device', required=True, help='Explicitly granted emulator only')
snapshot.add_argument('--out', type=Path, required=True)
snapshot.add_argument('--screenshot', action='store_true')
compare = commands.add_parser('compare')
compare.add_argument('before', type=Path)
compare.add_argument('after', type=Path)
compare.add_argument('--positions', action='store_true')
args = parser.parse_args()

if args.command == 'action':
    health = fixture_request(args.fixture, '/health')
    if health.get('synthetic') is not True:
        raise SystemExit('Refusing to control a non-synthetic endpoint')
    print(json.dumps(fixture_request(args.fixture, '/fixture/action', {'action': args.action, 'id': args.id}), indent=2))
elif args.command == 'snapshot':
    if not args.device.startswith('emulator-'):
        raise SystemExit('This helper is restricted to an explicitly owned emulator')
    adb = ['adb', '-s', args.device]
    subprocess.run(adb + ['shell', 'uiautomator', 'dump', '/sdcard/lifecycle-review.xml'], check=True, timeout=30, stdout=subprocess.DEVNULL)
    xml = subprocess.check_output(adb + ['exec-out', 'cat', '/sdcard/lifecycle-review.xml'], timeout=10)
    root = ET.fromstring(xml)
    nodes = list(root.iter('node'))
    cards = []
    for node in nodes:
        title = node.get('text', '')
        if re.fullmatch(r'Lifecycle (?:session \d{2}|new \d+)', title):
            cards.append({'title': title, 'bounds': list(map(int, re.findall(r'\d+', node.get('bounds', ''))))})
    text = '\n'.join(node.get('text', '') for node in nodes)
    if not cards and 'Ongoing synthetic work in Lifecycle' not in text:
        raise SystemExit('Refusing capture: synthetic lifecycle list/conversation is not visible')
    cards.sort(key=lambda card: card['bounds'][1])
    receipt = {'synthetic': True, 'cards': cards,
        'restartExplanation': 'Codex is preparing to restart' in text,
        'restartHeading': 'Codex restarting' in text,
        'safeRetry': 'before resending a reply' in text,
        'conversationVisible': 'Ongoing synthetic work in Lifecycle' in text}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(receipt, indent=2) + '\n')
    if args.screenshot:
        args.out.with_suffix('.png').write_bytes(subprocess.check_output(adb + ['exec-out', 'screencap', '-p'], timeout=10))
    print(json.dumps(receipt, indent=2))
else:
    before = json.loads(args.before.read_text()); after = json.loads(args.after.read_text())
    if not before.get('synthetic') or not after.get('synthetic'):
        raise SystemExit('Only synthetic lifecycle receipts may be compared')
    common = {card['title'] for card in before['cards']} & {card['title'] for card in after['cards']}
    if not common:
        raise SystemExit('No shared visible cards: inspect viewport/anchor before claiming stability')
    assert [c['title'] for c in before['cards'] if c['title'] in common] == [c['title'] for c in after['cards'] if c['title'] in common], 'Visible session order changed'
    if args.positions:
        assert [c['title'] for c in before['cards']] == [c['title'] for c in after['cards']], 'Visible membership changed during fixed-position review'
        old = {c['title']: c['bounds'] for c in before['cards']}
        assert all(old[c['title']] == c['bounds'] for c in after['cards'] if c['title'] in common), 'Visible card bounds moved'
    print(json.dumps({'commonVisibleCards': len(common), 'orderStable': True, 'boundsStable': args.positions}))
