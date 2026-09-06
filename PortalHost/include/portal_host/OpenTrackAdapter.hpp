#pragma once
#include "portal/Geometry.hpp"
#include <array>
#include <cstddef>
#include <optional>
namespace portal_host {
// x/y/z centimeters, yaw/pitch/roll degrees, matching pinned VRto3D TOpenTrack.
using OpenTrackPose=std::array<double,6>;
std::optional<OpenTrackPose> toOpenTrack(const portal::RigidTransform& head);
std::array<std::byte,48> encodeOpenTrack(const OpenTrackPose& pose);
}
