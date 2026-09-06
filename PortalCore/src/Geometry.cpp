#include "portal/Geometry.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace portal {
bool finite(Vec3 v) { return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z); }
bool validQuaternion(Quaternion q) {
    const double n=q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w;
    return std::isfinite(n)&&std::abs(n-1.0)<=1e-3;
}
bool validTransform(const RigidTransform& t) { return finite(t.position)&&validQuaternion(t.orientation); }
Vec3 add(Vec3 a,Vec3 b) { return {a.x+b.x,a.y+b.y,a.z+b.z}; }
Vec3 subtract(Vec3 a,Vec3 b) { return {a.x-b.x,a.y-b.y,a.z-b.z}; }
double dot(Vec3 a,Vec3 b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
Quaternion multiply(Quaternion a,Quaternion b) {
    return {a.w*b.x+a.x*b.w+a.y*b.z-a.z*b.y,a.w*b.y-a.x*b.z+a.y*b.w+a.z*b.x,
            a.w*b.z+a.x*b.y-a.y*b.x+a.z*b.w,a.w*b.w-a.x*b.x-a.y*b.y-a.z*b.z};
}
Vec3 rotate(Quaternion q,Vec3 v) {
    const double n=std::sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w);
    q={q.x/n,q.y/n,q.z/n,q.w/n};
    auto r=multiply(multiply(q,{v.x,v.y,v.z,0}),{-q.x,-q.y,-q.z,q.w});return {r.x,r.y,r.z};
}
Vec3 transformPoint(const RigidTransform& t,Vec3 p) { return add(t.position,rotate(t.orientation,p)); }
Vec3 inverseTransformPoint(const RigidTransform& t,Vec3 p) {
    auto q=t.orientation;return rotate({-q.x,-q.y,-q.z,q.w},subtract(p,t.position));
}
RigidTransform compose(const RigidTransform& a,const RigidTransform& b) {
    return {transformPoint(a,b.position),multiply(a.orientation,b.orientation)};
}
std::optional<EyeFrustum> portalFrustum(const PortalGeometry& p,Vec3 worldEye) {
    if(!validTransform(p.worldFromPortal)||!finite(worldEye)||!std::isfinite(p.widthMeters)||
       !std::isfinite(p.heightMeters)||p.widthMeters<=0||p.heightMeters<=0) return {};
    auto e=inverseTransformPoint(p.worldFromPortal,worldEye);
    if(!finite(e)||e.z<=.10)return {};
    EyeFrustum f{(-p.widthMeters/2-e.x)/e.z,(p.widthMeters/2-e.x)/e.z,
                 (-p.heightMeters/2-e.y)/e.z,(p.heightMeters/2-e.y)/e.z};
    if(!std::isfinite(f.left)||!std::isfinite(f.right)||!std::isfinite(f.bottom)||!std::isfinite(f.top)||
       f.left>=f.right||f.bottom>=f.top)return {};
    return f;
}
std::optional<std::array<Vec3,2>> eyePositions(const RigidTransform& head,double separation) {
    if(!validTransform(head)||!std::isfinite(separation)||separation<=0||separation>.2)return {};
    std::array<Vec3,2> eyes{transformPoint(head,{-separation/2,0,0}),transformPoint(head,{separation/2,0,0})};
    if(!finite(eyes[0])||!finite(eyes[1]))return {};return eyes;
}
Quaternion windowViewOrientation(const PortalGeometry& p) { return p.worldFromPortal.orientation; }
Mat4 toUnrealReversedZ(const EyeFrustum& f,double nearUnits) {
    if(!std::isfinite(f.left)||!std::isfinite(f.right)||!std::isfinite(f.bottom)||!std::isfinite(f.top)||
       f.right<=f.left||f.top<=f.bottom||!std::isfinite(nearUnits)||nearUnits<=0)
        throw std::invalid_argument("invalid frustum or near distance");
    const double dx=f.right-f.left,dy=f.top-f.bottom;
    Mat4 m{2/dx,0,0,0, 0,2/dy,0,0, -(f.right+f.left)/dx,-(f.top+f.bottom)/dy,0,1, 0,0,nearUnits,0};
    for(double x:m)if(!std::isfinite(x))throw std::invalid_argument("projection overflow");
    return m;
}
double nearMetersToUnreal(double meters,double worldToMeters) {
    if(!std::isfinite(meters)||meters<=0||!std::isfinite(worldToMeters)||worldToMeters<=0||
       !std::isfinite(meters*worldToMeters))throw std::invalid_argument("invalid world scale");
    return meters*worldToMeters;
}
std::optional<PortalGeometry> initialPortal(const RigidTransform& head,double aspect,std::uint64_t revision) {
    if(!validTransform(head)||!std::isfinite(aspect)||aspect<=0)return {};
    auto forward=rotate(head.orientation,{0,0,-1});
    const double horizontal=std::hypot(forward.x,forward.z);
    // A nearly vertical gaze has no meaningful yaw: use horizontal head-right to retain yaw.
    if(horizontal<1e-6) {auto right=rotate(head.orientation,{1,0,0});forward={right.z,0,-right.x};}
    const double length=std::hypot(forward.x,forward.z);if(length<1e-9)return {};
    forward={forward.x/length,0,forward.z/length};
    const double yaw=std::atan2(-forward.x,-forward.z);
    PortalGeometry p{{add(head.position,{forward.x,-.1,forward.z}),{0,std::sin(yaw/2),0,std::cos(yaw/2)}},.7*aspect,.7,revision};
    if(!std::isfinite(p.widthMeters)||!finite(p.worldFromPortal.position))return {};return p;
}
bool resizePortal(PortalGeometry& p,double width) {
    if(!std::isfinite(width)||!std::isfinite(p.widthMeters)||!std::isfinite(p.heightMeters)||
       p.widthMeters<=0||p.heightMeters<=0||p.revision==std::numeric_limits<std::uint64_t>::max())return false;
    width=std::clamp(width,.5,10.);const double height=p.heightMeters*(width/p.widthMeters);
    if(!std::isfinite(height)||height<=0)return false;
    p.widthMeters=width;p.heightMeters=height;++p.revision;return true;
}
bool resetPortalDimensions(PortalGeometry& p,double aspect) {
    if(!std::isfinite(aspect)||aspect<=0||!std::isfinite(.7*aspect)||p.revision==std::numeric_limits<std::uint64_t>::max())return false;
    p.widthMeters=.7*aspect;p.heightMeters=.7;++p.revision;return true;
}
}
