# Cinematic visionOS client: first build and QA

Everything on this branch was written without Xcode. Nothing here has been compiled or
run. This page is the script for the one session that builds it and checks it on the
headset. Work top to bottom: each part can be judged, and switched off, on its own.

App folder: `Moonlight-6Dof-Vision/Moonlight-6Dof-Vision`. New code is in `Cinema/`,
plus `Streaming/SurroundFeed.{h,m}`.

## 1. Build

**Result, 19 November 2026:** built clean on the first compile with no source changes,
on Xcode 27.2 with the visionOS 27.2 SDK, for the device destination. All nine
`Cinema/` files and `SurroundFeed.m` compiled. Xcode's Metal Toolchain component had to
be downloaded first (`xcodebuild -downloadComponent MetalToolchain`). The steps below
are kept for a fresh machine. Nothing has been run on a headset yet.

0. Restore the binary dependencies. `Moonlight-6Dof-Vision/Dependencies` is
   gitignored, so a fresh checkout does not have it. From the repository root:

   ```sh
   python3 tools/visionos-portal/bootstrap_apple_dependencies.py
   ```

   It clones the pinned donor into `External/` and copies the OpenSSL framework and
   Opus libraries and headers into place.
1. Open `Moonlight-6Dof-Vision.xcodeproj`. The project uses folder-synchronized
   groups, so the new files are already members of the target.
2. Confirm the Xcode SDK matches the headset's visionOS version. A mismatched SDK
   crashes before `main()` and looks like an app bug.
3. Build for the device, not the simulator: the donor's simulator Opus library is
   built for devices and the simulator linker rejects it. To check compilation
   without signing:

   ```sh
   xcodebuild -project Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj \
     -scheme Moonlight-6Dof-Vision -destination 'generic/platform=visionOS' \
     -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
   ```

   Expect compile errors; fix them in place. The likeliest spots:

| Where | What may not compile, and why |
| --- | --- |
| `Cinema/PlayerWindowView.swift` | Sizing the picture uses the documented point conversion on the window's corners; the property names on the frame (`min`, `max`) are from memory |
| `Cinema/RoomLightRig.swift` | Low risk: initializer labels and the colour type were confirmed against Apple's pages and match Hearth's working calls |
| `Cinema/SurroundStage.swift` | Passing an Objective-C render block to `playAudio(configuration:_:)`: may need `@Sendable` handling |
| `Cinema/StreamKeyboardMouse.swift` | GameController handler signatures and the C constant types from `Limelight.h` |
| `Cinema/DisplayCriteriaHint.swift` | `UIWindow.avDisplayManager`: listed for visionOS, never used here before |
| `Moonlight_6Dof_VisionApp.swift` | `.restorationBehavior` and `.defaultLaunchBehavior` on the player window |
| `Streaming/SurroundFeed.m` | C11 atomics inside an Objective-C file |

4. Duplicate the target to create the 6DoF app. In the copy, add `PORTAL_6DOF` to
   *Active Compilation Conditions* and give it its own bundle identifier. The
   cinematic target leaves the flag unset, which hides every 6DoF control.

Every Apple API named in the new code was checked against its documentation page for
name, parameters and visionOS availability. That confirms the calls exist; it does not
confirm they type-check together.

## 2. Nothing regressed

With **Effects off** the app should behave as it did before this branch.

- [ ] Pair, list apps, connect. The stream appears in the theater.
- [ ] Move, turn and corner-resize the screen. The shelf hides after three seconds.
- [ ] Gamepad input and rumble work.
- [ ] Disconnect shows the recovery dialog. Reconnect works.
- [ ] No 6DoF toggle, calibration or recentre prompt appears anywhere.
- [ ] Audio plays, in stereo and in 7.1.

## 3. Window and theater

- [ ] Launcher, *Start in a window* on: Connect opens the player window with picture and sound.
- [ ] Player window, *Theater*: the screen appears in the room; the window closes; the stream does not reconnect.
- [ ] Theater shelf, *Window*: the window returns; the stream does not reconnect.
- [ ] In the theater with a stream, press the Digital Crown. The player window appears with the stream still running.
- [ ] Close the player window. The stream ends and the launcher is usable.
- [ ] Resize the player window to odd shapes. The picture letterboxes and is never stretched.
- [ ] Audio through every switch. `Mac-onboarding.md` records an open bug where closing
      the Settings window after connecting stops audio. Window mode opens and closes
      windows around a live stream, so check sound continues when: the launcher closes,
      the player window closes on the way to the theater, and the theater closes on the
      way back. Diagnostics log `Audio scene resolved` each time the scene changes.

Not built: the theater screen does not yet appear where the window was. It appears in
front of the viewer at the saved distance.

## 4. The four open points

Apple's documentation does not settle these. Each has a fallback.

| Check | How | If it fails |
| --- | --- | --- |
| HDR in the room | Stream an HDR game with HDR on. Compare a bright highlight against the SDR white of a window beside it | Theater HDR needs the system video path. Leave HDR off in the theater for now |
| Refresh-rate hint | 60 fps stream, *Ask for a matching refresh rate* on, then off. Pan slowly and watch for judder. Diagnostics log `Display hint` | Stream at 90 fps from the PC |
| A 7.1 generator | 7.1 stream, Effects on, spatial audio on, arrangement *Surround bed*. *Now playing* should read `Surround bed, 8 channels`. Run the speaker check | Use *Placed speakers*, or *System* |
| Speaker sync | Arrangement *Placed speakers*. Play content with a centred voice and listen for echo or phasing between speakers | Stay on *Surround bed* |

## 5. Light

Effects on, Lighting Emission on, theater.

- [ ] First use asks for permission to sense surroundings.
- [ ] Room light: colour from the picture falls on the real floor and nearby surfaces, and changes with the scene.
- [ ] Left and right are correct: a picture that is red on the left lights the left of the room red.
- [ ] Top and bottom: if the floor takes the top of the picture's colour, turn on *Flip room light vertically*. Note which setting is right so the default can be fixed.
- [ ] Strength and reach sliders change what you see.
- [ ] Bias light: the wall behind the screen glows in the picture's average colour.
- [ ] A hard cut does not flash across the room.
- [ ] *Use the room's shape* off: note whether light still lands on real surfaces. If it does, the mesh is not needed.
- [ ] Real furniture in front of the screen hides it. Decide whether that is acceptable.
- [ ] Ten minutes of play: watch for dropped frames and heat. Diagnostics log `thermal scale` when the lights step down.

## 6. Sound

Effects on, spatial audio on, theater, 7.1 stream.

- [ ] Surround bed: the speaker check moves around you in order. Front left, centre, front right, side right, back right, back left, side left, low frequency.
- [ ] Turn your head: the channels stay fixed to the screen, not to your head.
- [ ] If side and back are swapped, the channel map in `SurroundStage.buildBed` is wrong for Apple's layout. Swap entries 4,5 with 6,7.
- [ ] Placed speakers: the same check, with speakers at real positions. *Centre speakers on me* moves the surrounds to your seat.
- [ ] Low-frequency level slider is audible in placed-speaker mode.
- [ ] Lip-sync delay: 0 ms should feel in sync. Raise it and confirm sound moves later.
- [ ] Diagnostics statistics show `spatial feed on` and a low `gaps` count. A rising count means the 40 ms base lead in `SurroundFeed.m` is too short.
- [ ] Switching to the window returns audio to the system path with no silence and no doubling.
- [ ] 5.1 and stereo streams also play in each arrangement.

## 7. Screen

- [ ] Curve slider bends the screen; the picture is not stretched. Corners and shelf stay attached.
- [ ] Tilt slider leans the screen.
- [ ] Save a preset, move the screen, *Use* the preset: size, distance, height, curve and tilt return.
- [ ] Quit and relaunch with *Remember size and distance* on: the screen comes back at its last size and distance.

## 8. Session

- [ ] Hold Menu and Options on the controller: the shelf appears.
- [ ] *Show statistics above the screen* shows live numbers.
- [ ] Pull the network briefly: the app retries up to three times.
- [ ] *Connect when the app opens*: relaunch and confirm one automatic connection.
- [ ] *Forward keyboard and mouse* (experimental): keys and pointer reach the host; nothing stays held after disconnect.

## 9. Record

For each part: works, works with changes, or switched off. Anything switched off gets
one line on what was seen, so the second pass starts from evidence.
