"""Real installed Global/worker + native Android attention; bounded original-receipt recovery."""

import argparse
import hashlib
import json
import os
import pwd
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from uuid import uuid4

from hollywood_backbone.bootstrap import initialize
from hollywood_backbone.store import Store
from lax_connector.journal import saved
from lax_connector.host import enroll as enroll_host, resources as host_resources
from lax_connector.linux_admin import provision

PROXY = """
import asyncio,json,sys
from pathlib import Path
from aiohttp import web,ClientSession
root=Path(sys.argv[1])
trace=[]
async def forward(req):
 if req.path=='/health':return web.json_response({'service':'owned-attention-fault-proxy'})
 if not req.path.startswith('/hollywood/v3/'):raise web.HTTPNotFound()
 body=await req.read()
 async with ClientSession() as session:
  async with session.request(req.method,'http://127.0.0.1:18772'+req.path_qs,data=body,headers={k:v for k,v in req.headers.items() if k.lower() in ('authorization','content-type')},allow_redirects=False) as response:
   data=await response.read()
   fault=req.method=='POST' and req.path.endswith('/response') and '/attention/' in req.path and response.status==200 and not (root/'lost-reply.json').exists()
   trace.append({'method':req.method,'path':req.path,'upstreamStatus':response.status,'acceptedReplyDropped':fault})
   (root/'trace.json').write_text(json.dumps(trace))
   if fault:
    (root/'lost-reply.json').write_bytes(data)
    return web.json_response({'error':'Qualification dropped the accepted response acknowledgement'},status=503)
   return web.Response(body=data,status=response.status,content_type='application/json')
app=web.Application();app.router.add_route('*','/{tail:.*}',forward)
web.run_app(app,host='127.0.0.1',port=18773,print=None)
"""


def main(spec):
    os.umask(0o077)
    assert os.geteuid() == 0 and socket.gethostname() == "ozzz-aosp"
    root = Path(spec["evidence"])
    root.mkdir(mode=0o711)
    # The private umask removes traversal from mkdir's requested mode.
    # Service users get exact-path traversal; private files/children stay 0600/0700.
    root.chmod(0o711)
    saved(
        root / "intent.json",
        {
            "scope": "One fresh native Global/worker, one owned emulator request, one authorized material review; synthetic marker only",
            "productionChanged": False,
            "physicalPhone": False,
            "runtime": spec["runtime"],
        },
    )
    for port in (18772, 18773, 18888):
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(("127.0.0.1", port))
    runtime = Path(spec["runtime"])
    python = str(runtime / "venv/bin/python")
    repo = Path(spec["nextcomp"])
    account = pwd.getpwnam("aosp")
    data = root / "gateway-data"
    data.mkdir(mode=0o700)
    os.chown(data, account.pw_uid, account.pw_gid)
    proxy_data = root / "proxy-data"
    proxy_data.mkdir(mode=0o700)
    os.chown(proxy_data, account.pw_uid, account.pw_gid)
    initialize(
        json.loads(Path(spec["databaseConfig"]).read_text()),
        root / "enterprise",
        "Native Android attention qualification",
    )
    install = json.loads((root / "enterprise/installation-intent.json").read_text())
    authority = install["authorityId"]
    owner = install["credentials"]["Owner"]
    global_id = install["credentials"]["Global"]["principalId"]
    room = install["globalRoomId"]
    host = str(uuid4())
    prefix = "attention_" + authority.replace("-", "")[:10]
    saved(
        Path("/opt/hetzner-blue-team/coordination/announcements") / ("native-android-attention-" + authority + ".json"),
        {"owner": "01a12c58-d21a-79b0-a512-0c3bc6dc0ee4", "host": "ozzz-aosp",
         "privateListeners": ["127.0.0.1:18772", "127.0.0.1:18773", "127.0.0.1:18888"],
         "scope": "Fresh finite Global/worker + owned Android notification qualification; no old owner/schedule resumes",
         "runtime": str(runtime), "credentialIntent": str(root / "enterprise/installation-intent.json"),
         "rollback": "Stop owned API/proxy/gateway children, Global unit, and stop/disable only this authority's worker units"})
    children = []
    gateway = None
    global_receipt = None
    instrument = None

    def launch(name, args, env=None, as_user=None, cwd=None):
        log = (root / (name + ".log")).open("ab")
        command = (["runuser", "-u", as_user, "--"] if as_user else []) + args
        child = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=env,
            cwd=cwd,
        )
        children.append(child)
        saved(root / (name + "-process.json"), {"pid": child.pid})
        return child

    def stop(child):
        if child and child.poll() is None:
            child.terminate()
            child.wait(timeout=20)

    def ready(base, child):
        for _ in range(100):
            assert child.poll() is None, "Owned service stopped before health"
            try:
                with urllib.request.urlopen(base + "/health", timeout=2) as response:
                    if response.status == 200:
                        return
            except OSError:
                time.sleep(0.25)
        raise RuntimeError("Owned service did not become healthy")

    def call(base, path, payload=None, token=None, name=None, method=None):
        if payload is not None:
            assert name
            saved(root / (name + "-intent.json"), {"path": path, "body": payload})
        request = urllib.request.Request(
            base + path,
            method=method or ("POST" if payload is not None else "GET"),
            data=None if payload is None else json.dumps(payload).encode(),
            headers={
                **({"Authorization": "Bearer " + token} if token else {}),
                "Content-Type": "application/json",
            },
        )
        with urllib.request.urlopen(request, timeout=15) as response:
            value = json.load(response)
        if name:
            saved(root / (name + "-receipt.json"), value)
        return value

    def adb(args, input=None, timeout=30, check=True):
        result = subprocess.run(
            ["runuser", "-u", "aosp", "--", spec["adb"], "-s", "emulator-5660", *args],
            input=input,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
        if check and result.returncode:
            raise RuntimeError("Owned emulator command failed; private output retained")
        return result

    def phone_read(name):
        result = adb(
            [
                "exec-out",
                "run-as",
                "co.fallsoft.pocket",
                "cat",
                "files/" + prefix + "-" + name + ".json",
            ],
            check=False,
        )
        if result.returncode:
            return None
        try:
            return json.loads(result.stdout)
        except ValueError:
            return None

    def phone_write(name, value):
        return adb(
            ["shell", "run-as", "co.fallsoft.pocket", "tee", "files/" + name],
            json.dumps(value).encode(),
        )

    def worker_receipts():
        return [
            json.loads(p.read_text())
            for p in (Path("/var/lib/harness") / authority / "workers").glob(
                "*/provision-receipt.json"
            )
        ]

    try:
        assert (
            adb(["shell", "getprop", "ro.boot.qemu.avd_name"]).stdout.decode().strip()
            == "harness_native_room"
        )
        assert (
            adb(["shell", "getprop", "sys.boot_completed"]).stdout.decode().strip()
            == "1"
        )
        before = adb(
            [
                "exec-out",
                "run-as",
                "co.fallsoft.pocket",
                "cat",
                "shared_prefs/pocket.xml",
            ],
            check=False,
        )
        (root / "private-android-before.xml").write_bytes(before.stdout)
        api = launch(
            "api",
            [
                python,
                "-m",
                "hollywood_backbone.server",
                "--database-config",
                spec["databaseConfig"],
                "--port",
                "18772",
            ],
            as_user="aosp",
        )
        ready("http://127.0.0.1:18772", api)
        proxy_file = root / "proxy.py"
        proxy_file.write_text(PROXY)
        proxy_file.chmod(0o644)
        proxy = launch(
            "proxy", [python, str(proxy_file), str(proxy_data)], as_user="aosp"
        )
        ready("http://127.0.0.1:18773", proxy)
        # Bootstrap two legitimate one-use pairings before admitting any native request.
        bootstrap_code = (
            "import{readFileSync,writeFileSync}from'node:fs';import{openStore}from '"
            + (repo / "server/store.mjs").as_uri()
            + "';const s=JSON.parse(readFileSync(0,'utf8'));const{db,secrets}=openStore(s.data);db.close();for(const[k,v]of Object.entries(s.keys))writeFileSync(s.data+'/'+k+'.key',v,{mode:0o600});writeFileSync(s.data+'/hollywood.json',JSON.stringify(s.config),{mode:0o600});console.log(JSON.stringify({adminToken:secrets.adminToken}));"
        )
        viewer_id = str(uuid4())
        viewer_token = secrets.token_urlsafe(40)
        call(
            "http://127.0.0.1:18772",
            "/hollywood/v3/principals",
            {
                "id": viewer_id,
                "name": "Attention viewer",
                "kind": "human",
                "enrollmentCredential": viewer_token,
            },
            owner["token"],
            "viewer",
        )
        call(
            "http://127.0.0.1:18772",
            "/hollywood/v3/rooms/" + room + "/membership",
            {
                "principalId": viewer_id,
                "can_read": True,
                "can_post": False,
                "can_request": False,
                "revision": 0,
            },
            owner["token"],
            "viewer-grant",
            method="PUT",
        )

        def binding(label):
            return {
                "url": "http://127.0.0.1:18773",
                "tokenFile": str(data / (label + ".key")),
                "authorityId": authority,
                "principalId": owner["principalId"] if label == "owner" else viewer_id,
                "defaultRoomId": room,
            }

        config = {
            "format": "hollywood-nextcomp-binding/v1",
            "installationId": str(uuid4()),
            "devices": {"owner": binding("owner")},
        }
        seeded = subprocess.run(
            [
                "runuser",
                "-u",
                "aosp",
                "--",
                "/usr/bin/node",
                "--input-type=module",
                "-e",
                bootstrap_code,
            ],
            input=json.dumps(
                {
                    "data": str(data),
                    "keys": {"owner": owner["token"], "viewer": viewer_token},
                    "config": config,
                }
            ),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        (root / "gateway-bootstrap.stderr").write_text(seeded.stderr)
        seeded.check_returncode()
        admin = json.loads(seeded.stdout)["adminToken"]

        def start_gateway(name):
            env = {
                **os.environ,
                "PORT": "18888",
                "POCKET_DATA": str(data),
                "POCKET_HOST_NAME": "Native attention qualification",
                "POCKET_EXECUTION_BACKEND": "hollywood",
                "CODEX_SOCKET": str(root / "no-stock-runtime.sock"),
                "POCKET_CODEX_COMMAND": "",
            }
            return launch(
                name,
                ["/usr/bin/node", str(repo / "server/index.mjs")],
                env=env,
                as_user="aosp",
                cwd=str(repo),
            )

        gateway = start_gateway("gateway-bootstrap")
        ready("http://127.0.0.1:18888", gateway)
        paired = {}
        for label in ("owner", "viewer"):
            code = call(
                "http://127.0.0.1:18888",
                "/api/pairing",
                {},
                admin,
                label + "-pairing-code",
            )
            paired[label] = call(
                "http://127.0.0.1:18888",
                "/api/pair",
                {"code": code["code"], "name": prefix + "-" + label},
                name=label + "-pairing",
            )
        stop(gateway)
        config["devices"] = {
            "owner": binding("owner"),
            **{paired[label]["id"]: binding(label) for label in ("owner", "viewer")},
        }
        config_file = data / "hollywood.json"
        config_file.write_text(json.dumps(config))
        os.chown(config_file, account.pw_uid, account.pw_gid)
        config_file.chmod(0o600)
        gateway = start_gateway("gateway")
        ready("http://127.0.0.1:18888", gateway)
        global_spec = {
            "installationIntent": str(root / "enterprise/installation-intent.json"),
            "approvedModelAuthFile": spec["approvedModelAuthFile"],
            "binary": str(runtime / "native/bin/codex"),
            "interpreter": python,
            "hollywoodUrl": "http://127.0.0.1:18772",
            "hostId": host,
            "model": "gpt-6.1-sol",
            "effort": "xhigh",
        }
        global_receipt = provision(global_spec)
        saved(root / "global-provision-receipt.json", global_receipt)
        call(
            "http://127.0.0.1:18772",
            "/hollywood/v3/placements",
            {
                "hostId": host,
                "name": "Native Android attention OZZZ",
                "identityFingerprint": hashlib.sha256(
                    Path("/etc/machine-id").read_bytes()
                ).hexdigest(),
                "capabilities": {"localProvisioning": True},
                "agentId": global_id,
            },
            owner["token"],
            "placement",
        )
        enrollment = enroll_host({
            "authorityId": authority,
            "hostId": host,
            "hollywoodUrl": "http://127.0.0.1:18772",
            "administratorCredential": owner,
            "policy": {
                "maxConcurrent": host_resources()["cpuSlots"],
                "reservedInteractive": 1 if host_resources()["cpuSlots"] > 1 else 0,
                "controlAgentIds": [global_id],
                "memoryPerTurnBytes": 2 * 1024**3,
                "reserveMemoryBytes": 4 * 1024**3,
                "sampleMaxAgeSeconds": 20,
                "connectorMaxAgeSeconds": 20,
            },
        })
        saved(root / "host-enrollment.json", enrollment)
        # Sample once before any agent can admit a native request; the owned
        # deterministic controller keeps that original host policy fresh.
        controller_config = enrollment["configPath"]
        sampled = subprocess.run(
            [python, "-m", "lax_connector.host", "--once", controller_config],
            capture_output=True, text=True, check=False,
        )
        (root / "host-preflight.stderr").write_text(sampled.stderr)
        sampled.check_returncode()
        saved(root / "host-initial-sample.json", json.loads(sampled.stdout))
        launch("host-controller", [python, "-m", "lax_connector.host", "--serve", controller_config])
        global_unit = "lax-attention-global-" + authority.replace("-", "")[:12]
        saved(root / "global-service-intent.json", {"unit": global_unit})
        subprocess.run(
            [
                "systemd-run",
                "--unit=" + global_unit,
                "--uid=" + global_receipt["linuxUser"],
                "--property=UMask=0077",
                "--property=MemoryMax=3G",
                "--property=RuntimeMaxSec=950",
                "--property=WorkingDirectory=" + global_receipt["home"] + "/work",
                python,
                "-m",
                "lax_connector.connector",
                "--config",
                global_receipt["connectorConfig"],
                "--serve",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        marker = "NATIVE_ANDROID_" + secrets.token_hex(8)
        question = "Which marker should this attention proof contain? " + prefix
        worker_instruction = (
            "First ask a Hollywood question using id attention_marker, header Marker, and question exactly "
            + json.dumps(question)
            + ". Allow a free-form answer. Wait for the attributable human response. Do not infer a marker or poll models. After the answer, write exactly the supplied marker followed by a newline to attention-proof.txt in your workspace with a single shell command, verify its bytes, complete the bounded native goal using update_goal, and submit this exact work with connector-hashed artifactPaths attention-proof.txt and current revision. Report only the artifact/reference needed for review. Do not start other work."
        )
        request = (
            "Run one finite native Android attention deliverable. Create a fresh engineer, its Engineering room and home role, and provision separate non-sudo Linux execution. Create one goal titled Native Android attention, objective Obtain an attributable human marker through native Hollywood attention and verify the resulting artifact, acceptance The original worker receives the exact authorized human answer, its file contains that answer followed by a newline, and Global independently verifies the canonical response, artifact and connector hash before explicitly accepting work and goal. Authorize reviewBudget=1, one material completion review. Add and assign exactly one work to the engineer with these instructions: "
            + worker_instruction
            + " End the initial turn promptly after delegation without polling. In the one separately admitted completion review, use hollywood_attention_list in the worker room and hollywood_attention_read for the exact work delivery's question to independently verify the response author and marker. Read the work evidence, inspect its actual file bytes/hash with your host administration, and explicitly accept the work and goal with the checked facts. Keep your ongoing native role active. Do not self-chain, schedule, create extra workers or restart historical work."
        )
        settings = {
            "case": prefix,
            "base": "http://127.0.0.1:18880",
            "ownerProfile": prefix + "_owner",
            "viewerProfile": prefix + "_viewer",
            "ownerToken": paired["owner"]["token"],
            "viewerToken": paired["viewer"]["token"],
            "ownerDevice": paired["owner"]["id"],
            "viewerDevice": paired["viewer"]["id"],
            "globalRoom": room,
            "globalAgent": global_id,
            "request": request,
            "question": question,
            "marker": marker,
        }
        saved(root / "private-android-settings.json", settings)
        for name in ("app-qualification.apk", "app-qualification-androidTest.apk"):
            path = (
                repo
                / "android/app/build/outputs/apk"
                / (
                    "qualification"
                    if name == "app-qualification.apk"
                    else "androidTest/qualification"
                )
                / name
            )
            saved(
                root / (name + "-install-intent.json"),
                {
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "target": "emulator-5660",
                },
            )
            installed = adb(["install", "-r", str(path)], timeout=120)
            saved(
                root / (name + "-install-receipt.json"),
                {
                    "returncode": installed.returncode,
                    "stdout": installed.stdout.decode(),
                    "stderr": installed.stderr.decode(),
                },
            )
        adb(["reverse", "tcp:18880", "tcp:18888"])
        phone_write("native-attention-settings.json", settings)
        instrument = launch(
            "instrumentation",
            [
                "runuser",
                "-u",
                "aosp",
                "--",
                spec["adb"],
                "-s",
                "emulator-5660",
                "shell",
                "am",
                "instrument",
                "-w",
                "-e",
                "class",
                "co.fallsoft.pocket.HollywoodNativeAttentionJourneyTest#notificationAnswerRecoversOriginalReceiptAcrossDisconnect",
                "co.fallsoft.pocket.test/androidx.test.runner.AndroidJUnitRunner",
            ],
        )
        disconnected = False
        restarted = False
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            phase = phone_read("phase")
            if phase and phase["phase"] == "ready-for-offline" and not disconnected:
                stop(gateway)
                disconnected = True
                saved(
                    root / "gateway-disconnect.json",
                    {
                        "priorPid": gateway.pid,
                        "scope": phase["scope"],
                        "state": "stopped",
                    },
                )
                phone_write(
                    prefix + "-gateway-offline.json", {"state": "owned-gateway-stopped"}
                )
            if phase and phase["phase"] == "offline-intent-saved" and not restarted:
                saved(
                    root / "private-offline-intent.json", phone_read("offline-intent")
                )
                gateway = start_gateway("gateway-reconnected")
                ready("http://127.0.0.1:18888", gateway)
                restarted = True
            result = phone_read("result")
            if result:
                saved(root / "android-result.json", result)
                break
            if instrument.poll() is not None:
                raise RuntimeError(
                    "Android journey stopped without a success receipt; inspect original private trace"
                )
            time.sleep(1)
        else:
            raise RuntimeError("Bounded native Android journey did not settle")
        assert disconnected and restarted
        lost = json.loads((proxy_data / "lost-reply.json").read_text())
        trace = json.loads((proxy_data / "trace.json").read_text())
        answer_posts = [
            r
            for r in trace
            if r["method"] == "POST" and r["path"].endswith("/response")
        ]
        assert len(answer_posts) == 1 and answer_posts[0]["acceptedReplyDropped"]
        assert any(
            r["method"] == "GET" and "/commands/phone-attention:" in r["path"]
            for r in trace
        )
        store = Store(json.loads(Path(spec["databaseConfig"]).read_text()))
        try:
            goal = store.goal_read(owner["principalId"], result["goalId"])
            assert goal["goal"]["state"] == "accepted" and len(goal["plan"]) == 1
            work = goal["plan"][0]
            assert work["state"] == "accepted"
            worker = worker_receipts()[0]
            value = (Path(worker["workspace"]) / "attention-proof.txt").read_bytes()
            assert value == (marker + "\n").encode()
            assert work["evidence"][0]["sha256"] == hashlib.sha256(value).hexdigest()
            with store.pool.connection() as c:
                # Business acceptance precedes the review turn's final output.
                # Observe its original delivery to terminal before cleanup.
                settle_deadline = time.monotonic() + 120
                while True:
                    deliveries = c.execute(
                        "SELECT * FROM hb_delivery WHERE authority=%s", (authority,)
                    ).fetchall()
                    if len(deliveries) == 3 and all(
                        d["state"] == "completed" for d in deliveries
                    ):
                        break
                    if len(deliveries) > 3 or any(
                        d["state"] in ("failed", "cancelled", "unknown")
                        for d in deliveries
                    ):
                        raise RuntimeError("Original native workflow did not complete cleanly")
                    if time.monotonic() >= settle_deadline:
                        raise RuntimeError("Original review still unsettled; inspect without replay")
                    time.sleep(0.5)
                contexts = c.execute(
                    "SELECT DISTINCT native_thread FROM hb_delivery WHERE authority=%s",
                    (authority,),
                ).fetchall()
                assert len(contexts) == 2
        finally:
            store.close()
        saved(
            root / "result.json",
            {
                "state": "passed-real-native-android-attention-recovery",
                "authorityId": authority,
                "nativeTurns": 3,
                "nativeContexts": 2,
                "humanRequests": 1,
                "authorizedMaterialReviews": 1,
                "nativeAndroidNotification": True,
                "nativePendingIntentOpen": True,
                "nativeRemoteInputAnswer": True,
                "inactiveOriginProfileAndViewerDraftPreserved": True,
                "gatewayDisconnectRecovered": True,
                "acceptedAnswerPostCount": 1,
                "lostAnswerAcknowledgementRecoveredByOriginalReceipt": True,
                "canonicalHumanAnswerAndArtifactVerified": True,
                "globalWorkAndGoalAccepted": True,
                "modelPolling": False,
                "originalTaskResubmitted": False,
                "realFcmVerified": False,
                "physicalPhone": False,
                "productionChanged": False,
            },
        )
        print(
            "Actual native Global/worker Android notification and original-answer recovery passed"
        )
    finally:
        for worker in worker_receipts():
            subprocess.run(
                ["systemctl", "disable", "--now", worker["unit"]],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        if global_receipt:
            subprocess.run(
                [
                    "systemctl",
                    "stop",
                    "lax-attention-global-" + authority.replace("-", "")[:12],
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        for child in reversed(children):
            stop(child)
        final = adb(
            [
                "exec-out",
                "run-as",
                "co.fallsoft.pocket",
                "cat",
                "shared_prefs/pocket.xml",
            ],
            check=False,
        )
        (root / "private-android-final.xml").write_bytes(final.stdout)
        saved(
            root / "cleanup.json",
            {
                "ownedApiProxyGatewayStopped": True,
                "globalStopped": True,
                "workerUnitsStoppedAndDisabled": [w["unit"] for w in worker_receipts()],
                "physicalPhone": False,
                "productionChanged": False,
            },
        )


def cleanup(spec):
    root = Path(spec["evidence"])
    intent = root / "enterprise/installation-intent.json"
    if not intent.exists():
        return
    authority = json.loads(intent.read_text())["authorityId"]
    subprocess.run(
        [
            "systemctl",
            "stop",
            "lax-attention-global-" + authority.replace("-", "")[:12],
        ],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    for p in (Path("/var/lib/harness") / authority / "workers").glob(
        "*/provision-receipt.json"
    ):
        row = json.loads(p.read_text())
        assert row["authorityId"] == authority
        subprocess.run(
            ["systemctl", "disable", "--now", row["unit"]],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", type=Path, required=True)
    parser.add_argument("--cleanup", action="store_true")
    args = parser.parse_args()
    spec = json.loads(args.spec.read_text())
    cleanup(spec) if args.cleanup else main(spec)
