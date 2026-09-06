#include "portal_host/OpenTrackAdapter.hpp"
#include <algorithm>
#include <bit>
#include <cmath>
#include <numbers>

namespace portal_host {
std::optional<OpenTrackPose> toOpenTrack(const portal::RigidTransform& head) {
    if(!portal::validTransform(head))return {};
    auto q=head.orientation;const double n=std::sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w);
    q={q.x/n,q.y/n,q.z/n,q.w/n};
    // VRto3D/utils/vrmath/vrmath.h:175 gives q = Ry(yaw)*Rx(pitch)*Rz(roll).
    // OpenTrackThread:977 calls helper(Roll,Pitch,-Yaw). Do not use a generic XYZ Euler conversion.
    const double sinPitch=std::clamp(2*(q.w*q.x-q.y*q.z),-1.,1.);
    const double pitch=std::asin(sinPitch);double yaw,roll;
    if(std::abs(sinPitch)<1-1e-12) {
        yaw=std::atan2(2*(q.x*q.z+q.w*q.y),1-2*(q.x*q.x+q.y*q.y));
        roll=std::atan2(2*(q.x*q.y+q.w*q.z),1-2*(q.x*q.x+q.z*q.z));
    } else { // At gimbal lock choose roll=0 and preserve the represented rotation.
        yaw=std::atan2(2*(q.w*q.y-q.x*q.z),1-2*(q.y*q.y+q.z*q.z));roll=0;
    }
    constexpr double degrees=180/std::numbers::pi;
    OpenTrackPose p{-100*head.position.x,-100*head.position.y,100*head.position.z,-yaw*degrees,pitch*degrees,roll*degrees};
    for(double v:p)if(!std::isfinite(v))return {};return p;
}
std::array<std::byte,48> encodeOpenTrack(const OpenTrackPose& pose) {
    std::array<std::byte,48>b{};
    for(std::size_t i=0;i<6;++i){auto value=std::bit_cast<std::uint64_t>(pose[i]);
        for(std::size_t j=0;j<8;++j)b[i*8+j]=std::byte((value>>(j*8))&255);}
    return b;
}
}
