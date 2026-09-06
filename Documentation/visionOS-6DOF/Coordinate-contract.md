# Portal coordinate contract

Implementation: `PortalCore/include/portal/Geometry.hpp`, `PortalCore/src/Geometry.cpp`.
All world transforms use right-handed meters: +X right, +Y up, and initial viewer-facing portal +Z.
The head looks along its local -Z. Quaternions are x,y,z,w and rotate local vectors into the parent/world frame.
`worldFromHead` already includes device-to-head calibration. Positions and orientation must be finite;
quaternion squared norm must be within 0.001 of one, with normalization for geometric calculations.

`portalFrustum` first applies the inverse portal rigid transform to the world eye. For local eye
`(ex,ey,ez)` it returns signed tangents:

```
left   = (-width/2 - ex) / ez
right  = ( width/2 - ex) / ez
bottom = (-height/2 - ey) / ez
top    = ( height/2 - ey) / ez
```

Reject local `ez <= 0.10 m`, invalid transforms, nonpositive dimensions, nonfinite results, or collapsed tangent intervals.
An eye outside the rectangle remains valid: both horizontal or vertical tangents can have the same sign.
The 2.4 × 1.35 m mathematical fixture at eye (0,0,2) gives (-0.6,+0.6,-0.3375,+0.3375).
It is a mathematical fixture, not the application's initial physical size.

`eyePositions` transforms head-local (-IPD/2,0,0) and (+IPD/2,0,0) into world space.
Head yaw and roll therefore affect both eye positions. `windowViewOrientation` comes exclusively from the
portal orientation: both eyes retain portal-aligned right/up/forward axes while their origins move independently.
A common rigid transform applied to portal and head preserves both eyes' frusta.

`initialPortal` projects head forward horizontally, creates an upright yaw-only portal one meter ahead and
0.1 m below the head, and uses height 0.7 m and width `0.7 * singleEyeContentAspect`.
For a nearly vertical head direction it derives yaw from head-right. Corner resize preserves aspect and clamps
width to 0.5–10 m. Reset size restores 0.7 m height/content aspect while preserving portal center and yaw.
Geometry-changing helpers increment revision; the initial helper uses the supplied revision.

## Unreal projection boundary

`Mat4` is a column-major array used with column vectors. Its input is projection/view space
**+X screen-right, +Y screen-up, +Z camera-forward**, after Unreal's world/view-axis conversion.
This is not an Unreal world-space matrix. Unreal world axes are +X forward, +Y right, +Z up;
the corresponding portal-space vector mapping is `UE = (-portal.z, portal.x, portal.y) * world_to_meters`.
Window orientation must undergo the corresponding change of basis in the integration layer.

For `dx=right-left`, `dy=top-bottom`, the mathematical matrix rows are:

```
[ 2/dx   0     -(right+left)/dx   0    ]
[ 0      2/dy  -(top+bottom)/dy   0    ]
[ 0      0      0                near ]
[ 0      0      1                0    ]
```

Clip W is forward distance. Reversed-Z depth is `near / forwardDistance`, with an infinite far plane.
`nearMetersToUnreal(nearMeters, world_to_meters)` performs the meter-to-engine conversion once.
If the hook already supplies Unreal near units, pass them directly to `toUnrealReversedZ`.

The pinned source comparison is:

- `External/UEVR-6DOF-Window/src/mods/vr/runtimes/OpenXR.cpp:535–552`: signed tangent sums,
  negative center offsets, column-major initializer, near at [3][2], W at [2][3].
- `.../runtimes/OpenVR.cpp:244–263`: opposite raw horizontal naming/sign convention; convert to
  this contract's signed tangents before constructing the matrix.
- `.../FFakeStereoRenderingHook.cpp:4885`: runtime-to-Unreal axis-conversion matrix.
- `.../FFakeStereoRenderingHook.cpp:5174–5189`: near is read from the engine matrix and the runtime matrix
  is assigned to float/double engine storage. Do not transpose a second time when using the existing GLM boundary.

This establishes a source-derived convention, not measured runtime equivalence. Engine hook behavior,
per-eye corner placement, near units, and resulting SteamVR transforms still require runtime validation.

## Authored validation; not executed

`PortalCore/tests/GeometryTests.cpp` checks centered and translated eyes, rotated/relocated portals,
off-rectangle eyes, rejection cases, both eyes under roll/yaw/IPD changes, near scaling, and application size semantics.
The independent corner oracle constructs world-space corner-eye rays, dots them against the portal basis,
and checks normalized corners at tolerance 1e-5. Fixture values are recorded in `PortalCore/fixtures/geometry.json`.
Assertions are retained in Release with `/UNDEBUG` or `-UNDEBUG`.

At the user's request no builds, tests, deliberate fixture failures, or debugging were run during this phase.
After the Mac handoff, execute the standalone CMake/CTest commands in the implementation plan and deliberately
break/restore one expected corner once to establish that Release validation detects failure.
