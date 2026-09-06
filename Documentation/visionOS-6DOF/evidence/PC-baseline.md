# Unmodified PC baseline — deferred

No SteamVR/VRto3D/Sunshine installation, launch, capture, driver setting inspection or tracking sweep occurred in the Windows source-authoring pass. There is no working-baseline claim, screenshot, measured CSV, installed-version number or validated game profile to report. profiles/baseline.json explicitly records unknowns.

For the later baseline gate: use the pinned unmodified UEVR fb31341 in OpenVR Native Stereo, prove a static valid HMD before synthetic OpenTrack, choose full SBS 3840x1080 and capture the presentation display in Windows Moonlight. Preserve distinct eye labels and all four corners. Read the installed pinned driver's settings before recording exact tracking-filter, convergence, reprojection or SBS configuration keys; no guessed driver keys are provided here.

Record runtime HMD pose, eye separation, captured eye order/aspect, and x/y/z/yaw/pitch/roll sweeps. Baseline is normal masked VR and does not demonstrate off-axis portal projection. Use PortalHost plus tools/visionos-portal/send_pose.py for portal source sweeps after the unchanged bridge works.

Future launch sequence: configure capture display and Sunshine, start SteamVR/VRto3D with static HMD, inject unchanged UEVR into the selected game, prove Native Stereo, enable synthetic tracking, connect Windows Moonlight, then record exact versions/settings/game save location and captures. Save the original profile before changing settings.

Rollback: disable PortalOutput and its diagnostics; restore saved UEVR game profile and original capture dimensions; use the unmodified pinned backend rather than reverting unrelated game settings. Stop the synthetic sender/PortalHost when complete. Source rollback may use a separate clean base checkout; do not overwrite local modifications.
