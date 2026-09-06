#pragma once
#include <array>
#include <cstdint>
#include <optional>

namespace portal {
struct Vec3 { double x,y,z; };
struct Quaternion { double x,y,z,w; };
struct RigidTransform { Vec3 position; Quaternion orientation; };
struct PortalGeometry { RigidTransform worldFromPortal; double widthMeters,heightMeters; std::uint64_t revision; };
struct EyeFrustum { double left,right,bottom,top; };
using Mat4=std::array<double,16>; // Column-major, column-vector multiplication.
bool finite(Vec3 v);
bool validQuaternion(Quaternion q); // Finite, squared norm within 1e-3 of unity.
bool validTransform(const RigidTransform& t);
Vec3 add(Vec3 a,Vec3 b);
Vec3 subtract(Vec3 a,Vec3 b);
double dot(Vec3 a,Vec3 b);
Quaternion multiply(Quaternion a,Quaternion b);
Vec3 rotate(Quaternion q,Vec3 v); // Requires validQuaternion; normalizes roundoff.
Vec3 transformPoint(const RigidTransform& t,Vec3 p);
Vec3 inverseTransformPoint(const RigidTransform& t,Vec3 p);
RigidTransform compose(const RigidTransform& a,const RigidTransform& b);
std::optional<EyeFrustum> portalFrustum(const PortalGeometry& portal,Vec3 worldEye);
std::optional<std::array<Vec3,2>> eyePositions(const RigidTransform& worldFromHead,double separationMeters);
Quaternion windowViewOrientation(const PortalGeometry& portal); // View right/up/-forward = portal X/Y/Z.
Mat4 toUnrealReversedZ(const EyeFrustum& frustum,double nearUnits); // Throws on invalid input.
double nearMetersToUnreal(double nearMeters,double worldToMeters); // Convert exactly once.
std::optional<PortalGeometry> initialPortal(const RigidTransform& head,double eyeContentAspect,std::uint64_t revision);
bool resizePortal(PortalGeometry& portal,double requestedWidthMeters); // Clamp .5..10, preserve aspect/pose.
bool resetPortalDimensions(PortalGeometry& portal,double eyeContentAspect); // .7m high, preserve pose.
}
