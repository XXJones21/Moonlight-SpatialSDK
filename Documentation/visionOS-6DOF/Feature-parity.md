# Quest to visionOS implementation map

This maps the inspected Quest contract in `Quest-UI-UX-Parity.md` to the authored
visionOS source. **Every runtime/visual result is pending.** Source presence is
not a visual-parity pass. Use V01–V10 in that contract to capture actual evidence.
Paths below are relative to the visionOS app source directory.

| Contract | Quest evidence | visionOS source evidence | Result / adaptation |
|---|---|---|---|
| C01 | Saved connection context | ConnectionViewModel restores selected server ID and its app | Source authored; device persistence pending |
| C02 | Paired left card launches | ConnectionView launch saves selection and opens shared experience | Source authored |
| C03 | Right card/server dialog | ServerPairingSheet with host/port and Cancel/Connect | Native SwiftUI keyboard/targeting |
| C04 | PIN direction and dismissal | PINSheet plus generation-scoped pairing events | Closing PIN leaves request alive |
| C05 | Pairing outcome/retry | Donor PairManager errors; no automatic launch | Source authored; host errors pending |
| C06 | App dropdown/loading/fallback | ConnectionViewModel current-host list, valid saved ID or first app | Source authored |
| C07 | Controller and stream column | ConnectionView live session size/stats, transport size and HDR result | Requested FPS is explicitly labeled |
| C08 | No-connection immersive entry | Preview checkerboard and local controls | Source authored; stereo orientation pending |
| C09 | Settings over active stream | Stable-value WindowGroup, experience-owned coordinator | Same window reused; closing it has no teardown |
| S01 | Six resolution choices | StreamConfigurationView per-eye values, EncodedStreamSize | Host filtering includes metadata |
| S02 | 30/60/90/120 selector | Host advertised mode tuples and requested FPS | Unknown device/display limits stated |
| S03 | Auto/H.264/HEVC/AV1 | VT hardware/host codec intersection and real stream masks | Device/profile support pending |
| S04 | Stereo/5.1/7.1 | Native core audio masks and AVAudioEngine channel paths | Actual route/layout pending |
| S05 | HDR request and fallback | Draft HDR, host/device filtering, negotiatedHDR feedback | PC exporter SDR-only initially |
| S06 | Full range | Real stream config colorRange and decoder conversion | Device color proof pending |
| S07 | Draft/Apply | Inline draft persists for next new connection | Active stream parameters retained |
| S08 | Capabilities on configuration entry | StreamCapabilities load/error/no-host/refresh and XML modes | No guessed device maxima |
| S09 | Automatic bitrate | StreamPreferences automatic calculation; bench preset in source | No invented primary bitrate slider |
| S10 | Four immediate preferences | ImmersiveOptionsView and persisted AppModel properties | Reflections visible/off/unavailable |
| S11 | Effects master | Shelf toggles AppModel effects without closing tracking | Audio mode changes immediately |
| P01 | Initial placement | PortalSceneController horizontal 1m, -0.1m, yaw only | Physical headset check pending |
| P02 | Base height and aspect | 0.7m base height and one-eye content aspect | Metadata excluded from aspect |
| P03 | Move and yaw | Scene-space drag and Y-axis rotation | Native pinch/gesture adaptation |
| P04 | Four corner scaling | Center/aspect preserved, 0.5–10m width, minimum hit target | Visible handles scale; hit target stays usable |
| P05 | Center-based uniform resize | Local-plane distance ratio and 0.5–10m physical width clamp | Center/orientation/aspect preserved |
| P06 | Size reset | Resize restores base dimensions only | Recenter is a separate action |
| P07 | Attached readable controls | Shelf follows bottom edge without scaling SwiftUI hierarchy | Native readable size retained |
| P08 | Local panel before stream | Backdrop/preview remains targetable while video is gated | Stalls retain placement controls |
| B01 | Bottom-centered shelf | Position at -height/2 - 0.06, independent SwiftUI scale | Native spacing/readability pending |
| B02 | Shelf inactivity | Three seconds, explicit action refresh and supported hover state | Headset usability pending |
| B03 | Settings/Resize/Immersive actions | Shared AppModel, size-only reset, effects toggle | No space dismissal from effects toggle |
| B04 | Disconnect dialog | Reconnect, Return to Home, Cancel in attached recovery UI | Held input released before teardown |
| B05 | Reconnect semantics | Previous host/app/preferences retained | Operation generations reject old callbacks |
| B06 | Dormant/system controls | Ordinary gamepad forwarding, no invented Home interception | System dismissal stops shared session |

The Options modal retains three primary cards in order: Configure/Hide Stream
Configuration (Device Capabilities), Reset Client Pairing (Clear cert & UID),
Immersive Options (Enable immersive features). Calibration is secondary Advanced
content. The client UID derives from the persistent public certificate, so reset
changes both identity and UID instead of retaining the donor's shared UID.

Panel-local lighting uses a 10 Hz GPU average with one-pixel readback, a glow
surface, local light and supported surroundings tint/dimming. Spatial audio uses
the latest calibrated head/listener and portal/source transforms. Route changes
and interruptions have native recovery handlers; acoustic correctness remains
unverified. Room-derived reflections are excluded with wall work.

Tracking loss pauses video and asks for explicit Recenter after recovery. This
is a deliberate correctness requirement beyond the Quest streaming surface:
head movement alone cannot identify a changed ARKit origin. Settings remains
reachable and returning home opens/reuses its window before leaving the space.

## Deferred evidence

- [ ] Capture matching Quest/visionOS launcher, dialogs, options and streaming states.
- [ ] Record native targeting, every corner, rotated resize, depth movement and timers.
- [ ] Exercise Settings closure, system dismissal, no-connection preview and recovery.
- [ ] Validate capabilities, PIN retries, removed apps, multiple saved servers and reset.
- [ ] Validate eye order, metadata cropping, color/HDR fallback, audio and input release.
- [ ] Record accepted visual/platform differences before claiming close visual parity.
