"""Exercise the built relay with the shipped sender and loopback test receivers.

This does not drive SteamVR or simulate a successful portal renderer.
Usage: python relay_smoke.py <repository-root>
"""
import csv
import json
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys
import time

repo = Path(sys.argv[1]).resolve()
out = Path(__file__).resolve().parent
sys.path.insert(0, str(repo / "tools/visionos-portal"))
from send_pose import decode_state

results = []
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as backend, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as tracking:
    backend.bind(("127.0.0.1", 14244))
    tracking.bind(("127.0.0.1", 14242))
    with open(out / "relay-smoke-host.txt", "w") as host_log:
        host = subprocess.Popen([
            str(repo / "PortalHost/build/Release/portal_host.exe"),
            "--listen", "14243", "--uevr-port", "14244", "--opentrack-port", "14242",
            "--peer", "127.0.0.1", "--trace-poses"], stdout=host_log, stderr=subprocess.STDOUT)
        sender = None
        try:
            time.sleep(0.25)
            assert host.poll() is None, "relay failed to start"
            for pattern in ("static", "sweep", "roll"):
                # Drop previous-run retry datagrams before observing the next session.
                for sock in (backend, tracking):
                    while select.select([sock], [], [], 0)[0]:
                        sock.recvfrom(2048)
                csv_path = out / f"relay-{pattern}.csv"
                args = [sys.executable, str(repo / "tools/visionos-portal/send_pose.py"),
                        "--host", "127.0.0.1", "--port", "14243", "--pattern", pattern,
                        "--duration", "3", "--csv", str(csv_path)]
                if pattern == "roll":
                    args += ["--reset-at", "1"]
                sender = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                states, motions, resets = [], [], 0
                deadline = time.monotonic() + 6
                while sender.poll() is None and time.monotonic() < deadline:
                    for sock in select.select([backend, tracking], [], [], 0.05)[0]:
                        packet, source = sock.recvfrom(2048)
                        assert source == ("127.0.0.1", 14243), source
                        if sock is backend:
                            if packet.startswith(b"P6DV"):
                                states.append(decode_state(packet))
                            elif packet.startswith(b"P6DR"):
                                assert len(packet) == 32
                                resets += 1
                            else:
                                raise AssertionError("unexpected backend datagram")
                        else:
                            assert len(packet) == 48, len(packet)
                            motions.append(struct.unpack("<6d", packet))
                if sender.poll() is None:
                    sender.terminate()
                    raise AssertionError("sender exceeded deadline")
                stdout, stderr = sender.communicate(timeout=2)
                assert sender.returncode == 0, stderr
                assert len(states) > 30 and len(motions) > 30, (len(states), len(motions))
                sent = list(csv.DictReader(csv_path.open()))
                session = int(sent[0]["sessionID"])
                states = [state for state in states if state.session == session]
                assert len(states) > 30
                unique_motion = len(set(motions))
                if pattern == "static":
                    assert all(all(abs(a - b) < 1e-8 for a, b in zip(m, (0, 0, 200, 0, 0, 0))) for m in motions)
                else:
                    assert unique_motion > 10, "moving pattern did not reach tracking receiver"
                if pattern == "roll":
                    assert resets > 0 and any(row["trackingEpoch"] == "2" for row in sent)
                    assert any(state.epoch == 2 for state in states)
                results.append({"pattern": pattern, "sent": len(sent), "forwardedStates": len(states),
                                "trackingPackets": len(motions), "uniqueMotionValues": unique_motion,
                                "resetPackets": resets, "senderResult": stdout.strip()})
                # Allow the session lease to expire before the next independent sender.
                time.sleep(0.35)
            result = {"passed": True, "scope": "relay plus synthetic sender; loopback receivers, no renderer or SteamVR input",
                      "listenPort": 14243, "backendPort": 14244, "trackingPort": 14242, "runs": results,
                      "shutdown": "test process terminated; cooperative shutdown not tested"}
            (out / "relay-smoke-results.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result, indent=2))
        finally:
            if sender is not None and sender.poll() is None:
                sender.terminate()
                sender.wait(timeout=5)
            if host.poll() is None:
                host.terminate()
            host.wait(timeout=5)
