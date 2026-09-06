"""Run the actual PortalHost -> PortalSession path without an injected renderer.
Usage: python session_integration.py <repository-root>
"""
import csv
import json
from pathlib import Path
import socket
import subprocess
import sys
import time

repo = Path(sys.argv[1]).resolve()
out = Path(__file__).resolve().parent
for port in (4243, 4244):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as check:
        check.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        check.bind(("127.0.0.1", port))

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as tracking_sink, open(out / "session-host.txt", "w") as host_log, open(out / "session-observations.csv", "w") as session_log:
    tracking_sink.bind(("127.0.0.1", 14242))
    host = subprocess.Popen([str(repo / "PortalHost/build/Release/portal_host.exe"),
                             "--peer", "127.0.0.1", "--opentrack-port", "14242"],
                            stdout=host_log, stderr=subprocess.STDOUT)
    receiver = subprocess.Popen([str(repo / "External/local-validation/portal_session_probe.exe")],
                                stdout=session_log, stderr=subprocess.STDOUT)
    sent_sessions = []
    try:
        time.sleep(0.3)
        assert host.poll() is None and receiver.poll() is None
        for pattern in ("static", "sweep", "roll"):
            csv_path = out / f"session-{pattern}-sent.csv"
            args = [sys.executable, str(repo / "tools/visionos-portal/send_pose.py"),
                    "--host", "127.0.0.1", "--pattern", pattern, "--duration", "2", "--csv", str(csv_path)]
            if pattern == "roll":
                args += ["--reset-at", "0.7"]
            run = subprocess.run(args, capture_output=True, text=True, timeout=5)
            assert run.returncode == 0, run.stderr
            with csv_path.open() as file:
                rows = list(csv.DictReader(file))
            sent_sessions.append(rows[0]["sessionID"])
            if pattern == "roll":
                assert any(row["trackingEpoch"] == "2" for row in rows)
            time.sleep(0.4)
        assert receiver.wait(timeout=8) == 0, "receiver did not observe both fresh and expired tracking"
    finally:
        if receiver.poll() is None:
            receiver.terminate()
            receiver.wait(timeout=5)
        if host.poll() is None:
            host.terminate()
        host.wait(timeout=5)

observations = list(csv.reader((out / "session-observations.csv").open()))
states = [row for row in observations if row[0] == "state"]
stale = [row for row in observations if row[0] == "stale"]
assert set(row[1] for row in states) == set(sent_sessions)
assert set(row[1] for row in stale) == set(sent_sessions)
assert any(row[1] == sent_sessions[-1] and row[2] == "2" for row in states)
result = {"passed": True, "scope": "built PortalHost and actual PortalSession.cpp outside the game; no renderer",
          "sessionsAccepted": len(sent_sessions), "stateObservations": len(states), "staleTransitions": len(stale),
          "resetEpoch2Accepted": True, "receiverExitedNormallyAfterLeaseExpiry": True,
          "runtimeTrackingDriven": False, "rendererValidated": False}
(out / "session-integration-results.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
