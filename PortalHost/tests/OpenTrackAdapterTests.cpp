#include "portal_host/OpenTrackAdapter.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
#include <numbers>
// Independently copied equations from pinned VRto3D utils/vrmath/vrmath.h:175.
portal::Quaternion driverQuaternion(double roll,double pitch,double yaw) {
    auto cr=cos(roll*.5),sr=sin(roll*.5),cp=cos(pitch*.5),sp=sin(pitch*.5),cy=cos(yaw*.5),sy=sin(yaw*.5);
    return {cr*sp*cy+sr*cp*sy,cr*cp*sy-sr*sp*cy,sr*cp*cy-cr*sp*sy,cr*cp*cy+sr*sp*sy};
}
int main() {
    for(double r:{-.7,0.,.8})for(double p:{-std::numbers::pi/2,-.4,0.,.3,std::numbers::pi/2})for(double y:{-2.,0.,1.2}) {
        portal::RigidTransform input{{.23,-.41,2.3},driverQuaternion(r,p,y)};
        auto ot=portal_host::toOpenTrack(input);assert(ot);constexpr auto rad=std::numbers::pi/180;
        auto result=driverQuaternion((*ot)[5]*rad,(*ot)[4]*rad,-(*ot)[3]*rad);
        auto q=input.orientation;double inner=q.x*result.x+q.y*result.y+q.z*result.z+q.w*result.w;
        assert(std::abs(std::abs(inner)-1)<1e-9);
        assert(std::abs(-(*ot)[0]/100-input.position.x)<1e-12);
        assert(std::abs(-(*ot)[1]/100-input.position.y)<1e-12);
        assert(std::abs((*ot)[2]/100-input.position.z)<1e-12);
        std::cout<<"source radians "<<r<<','<<p<<','<<y<<" -> OT degrees "<<(*ot)[3]<<','<<(*ot)[4]<<','<<(*ot)[5]<<'\n';
    }
}
