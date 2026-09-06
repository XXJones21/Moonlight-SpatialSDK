# Live Vision Pro preparation — 2026-09-06

The user requested stopping synthetic testing and proceeding toward headset
viewing. The user supplied AVP 192.168.0.182 and Windows wired IP 10.1.95.5.
No synthetic sender was started during this preparation.

## Current live result — 14:15 local

**Live AVP poses are now accepted by PortalHost.** Windows sees the connected
headset's traffic from **10.1.95.13**, despite the headset reporting
192.168.0.182. Sunshine independently reports the same peer. This is consistent
with address translation between the networks; the translating device has not
been identified.

A 15-second passive raw IPv4 capture observed 2,693 incoming UDP 4243 packets
from 10.1.95.13, including P6DR reset and P6DV v1 200-byte state packets with
trackingValid=1. See capture-live-raw.json. These counts include resets, not only
pose samples. Packet Monitor missed even active Moonlight media, so its earlier
empty captures are inconclusive and must not be cited as proof of no traffic.
The bounded captures ended and their diagnostic filters were removed.

Automatic review initially rejected correcting the source address. The user
explicitly approved the correction, and align-headset-peer.ps1 changed only the
remote address and display name of the existing firewall rule. The stable rule
name retains the original IP digits. Active scope was independently verified:
10.1.95.13, UDP 4243, portal_host.exe, all profiles. See
firewall-aligned-verified.json and the saved pre-change scope/rollback.

PortalHost was restarted as PID 46808 with `--peer 10.1.95.13 --trace-poses`.
portal-host-aligned.stdout.log now shows accepted, advancing pose sequences with
changing position/orientation and OpenTrack conversion. Receiver stderr is
empty. live-acceptance-summary.json summarizes complete logged records.
receiver-aligned.json supersedes receiver.json; earlier files remain historical.
The saved summary contains 660 complete accepted pose records. Sunshine logged
CLIENT DISCONNECTED at 14:15:09; the successful sample window predates that
disconnect. PortalHost was subsequently verified still listening as PID 46808,
ready for reconnection. This is not a claim of continuous tracking after disconnect.

No synthetic sender or Xcode changes were used. The game remains closed for this
check. UEVR consumption, client receipt of returned status, and valid portal
video remain unverified. A grey panel is expected without a valid video source.
Any needed client changes belong in the user's other session on the Mac.

The sections below retain the initial setup and capture blockers; the receiver
peer and firewall results above supersede their initial 192.168.0.182 scope.

## Receiver and network

- Windows chooses Ethernet source 10.1.95.5 and default gateway 10.1.95.1 for
  192.168.0.182. See route.json. This proves route selection, not connectivity.
- PortalHost was started hidden with `--peer 192.168.0.182 --trace-poses`.
  receiver.json records its PID and start time. It listens on UDP 4243 and
  forwards only accepted live state to loopback UEVR 4244 and OpenTrack 4242.
- Logs: portal-host-live.stdout.log and portal-host-live.stderr.log. Redirected
  stdout may buffer until enough traffic arrives. No live packet acceptance or
  return status has yet been verified.
- allow-headset.ps1 is syntax checked and prepared to add the persistent inbound
  rule `MoonlightPortal-VisionPro-1921680182-4243`, all network profiles, limited
  to peer 192.168.0.182, UDP local port 4243, and this built portal_host.exe.
  Automatic approval review initially rejected execution pending explicit user
  approval. The user subsequently approved the exact rule. It was applied at
  14:01:47 and its active address, program, protocol, port, and profile scope
  were independently verified at 14:02:08. See firewall-result.json and
  firewall-verified.json. The receiver was still listening as PID 47372.
- The existing rule blocking remote OpenTrack access remains unchanged.

Approval scope: permit this AVP to send packets to PortalHost even after reboot,
until the rule is removed. Rollback:

```powershell
Remove-NetFirewallRule -Name MoonlightPortal-VisionPro-1921680182-4243
```

## Capture and crash blockers

The active physical display is 2560x1440@144. Read-only mode enumeration and
CDS_TEST in the actual desktop user context reject 2560x736@60 with
DISP_CHANGE_BADMODE (-2). The mode was not changed. See display-mode-check.json
and inspect_display.cpp. The sandbox returned -1; the saved diagnostic uses the
actual user-context result. A matching physical/custom/virtual display mode is
still needed. Sunshine configuration has not been changed. Do not scale or crop
an ordinary desktop capture and treat it as metadata-valid portal video.

The user confirmed no flashing on bb7e3ef, but a later crash repeated the Present
hook recursion. See ../2026-09-06-renderer-protocol/README.md. No Present fix has
been applied, and no successful direct-capture/headset frame is established.

## Next hands-on checkpoint

**Completed user camera checkpoint:** The user observed the SteamVR loading
environment responding to AVP movement, then confirmed head pose maps cleanly
in the injected Hogwarts game with Portal Output, Room-Anchored Window, and
diagnostics off. The supplied screenshot shows bb7e3ef and all three unchecked.
Process inspection verified game PID 70796 loaded
External/local-validation/UEVR-portal-bb7e3ef/UEVRBackend.dll; saved settings
match OpenVR/Native Stereo and disabled output modes. PortalHost traces continue
to contain changing live poses, with empty stderr. This establishes ordinary
live head-driven game camera behavior by user observation. Detailed gamepad
independence, portal-mode projection/ownership, status and recovery remain open.

The user requested checking live camera movement in VRto3D while headset video
remains unavailable. The implementation plan now includes that intermediate
Windows checkpoint. Current game is closed, SteamVR and PortalHost are running,
and saved UEVR settings use OpenVR/Native Stereo with Portal Output, diagnostics,
and Room-Anchored Window off. Use bb7e3ef and verify its loaded module after user
injection. Correlate live relay poses with raw/standing OpenVR samples and then
observe ordinary game camera motion in VRto3D. The read-only pre-test sampler
result is runtime-before-camera.json; a retained pose does not establish fresh
live tracking. Keep the portal Present crash and off-axis validation open.

The firewall rule is applied and verified for observed source 10.1.95.13. Use the Vision Pro's existing Moonlight
client to connect to 10.1.95.5 with 6DoF Window enabled, 1280x720 per eye, 60 fps,
SDR HEVC and stereo audio. Leave Settings open for the known audio lifecycle
issue. Initially verify live pose arrival and status return while the game is
closed; live pose acceptance is now confirmed, and portal video will remain
unavailable until capture and rendering are working. Client receipt of returned
status is still unverified.
Do not launch another synthetic probe or bypass the client metadata gate.
