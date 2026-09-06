# First Apple build

The supplied project targets visionOS 27.0. Confirm installed SDK/device support
before adjusting that target. Windows authoring does not validate Apple APIs.

```sh
xcodebuild -showsdks
xcodebuild -list -project Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj
xcodebuild -project Moonlight-6Dof-Vision/Moonlight-6Dof-Vision.xcodeproj \
  -scheme Moonlight-6Dof-Vision -configuration Debug \
  -destination 'generic/platform=visionOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Set the signing team on Mac for hardware. Simulator success cannot establish
ARKit tracking, stereo correctness, codec throughput or comfort.
