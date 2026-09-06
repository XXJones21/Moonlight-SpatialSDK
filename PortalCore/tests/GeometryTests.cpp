#include "portal/Geometry.hpp"
#include <cassert>
#include <cmath>
#include <limits>

using namespace portal;
namespace {
bool close(double a, double b, double eps=1e-9) { return std::abs(a-b)<eps; }
void corners(const PortalGeometry& p, Vec3 eye) {
    auto f=portalFrustum(p,eye); assert(f);
    auto m=toUnrealReversedZ(*f,10.0);
    // Independent oracle: basis dot products of WORLD corner-eye rays.
    const auto right=rotate(p.worldFromPortal.orientation,{1,0,0});
    const auto up=rotate(p.worldFromPortal.orientation,{0,1,0});
    const auto forward=rotate(p.worldFromPortal.orientation,{0,0,-1});
    for (int sx : {-1,1}) for (int sy : {-1,1}) {
        auto corner=transformPoint(p.worldFromPortal,{sx*p.widthMeters/2,sy*p.heightMeters/2,0});
        auto ray=subtract(corner,eye);
        const double x=dot(ray,right)*100,y=dot(ray,up)*100,z=dot(ray,forward)*100;
        const double clipX=m[0]*x+m[8]*z;
        const double clipY=m[5]*y+m[9]*z;
        const double clipW=m[11]*z;
        assert(close(clipX/clipW,sx,1e-5)); assert(close(clipY/clipW,sy,1e-5));
        assert(close(m[14]/clipW,10.0/z));
    }
}
}
void geometryTests() {
    PortalGeometry p{{{0,0,0},{0,0,0,1}},2.4,1.35,1};
    auto f=portalFrustum(p,{0,0,2}); assert(f);
    assert(close(f->left,-.6)); assert(close(f->right,.6));
    assert(close(f->bottom,-.3375)); assert(close(f->top,.3375));
    f=portalFrustum(p,{.2,0,2}); assert(f);
    assert(close(f->left,-.7)); assert(close(f->right,.5));
    for (Vec3 eye : {Vec3{0,0,2},Vec3{.2,-.3,2},Vec3{3,2,2}}) corners(p,eye);
    for (double z : {.05,.1,0.,-1.}) assert(!portalFrustum(p,{0,0,z}));
    auto bad=p; bad.widthMeters=0; assert(!portalFrustum(bad,{0,0,2}));
    bad=p; bad.worldFromPortal.orientation={0,0,0,0}; assert(!portalFrustum(bad,{0,0,2}));
    bad=p; bad.worldFromPortal.orientation.w=2; assert(!portalFrustum(bad,{0,0,2}));
    assert(!portalFrustum(p,{std::numeric_limits<double>::quiet_NaN(),0,2}));
    const Quaternion roll{0,0,std::sin(.3),std::cos(.3)};
    const Quaternion yaw{0,std::sin(.4),0,std::cos(.4)};
    for (auto q : {roll,yaw,multiply(yaw,roll)}) for (double ipd : {.05,.064,.08}) {
        RigidTransform head{{.2,.1,2},q};
        auto eyes=eyePositions(head,ipd); assert(eyes);
        auto delta=subtract((*eyes)[1],(*eyes)[0]); assert(close(dot(delta,delta),ipd*ipd));
        corners(p,(*eyes)[0]); corners(p,(*eyes)[1]);
        assert(close(windowViewOrientation(p).w,1));
        RigidTransform common{{4,-3,7},multiply(yaw,roll)};
        auto moved=p; moved.worldFromPortal=compose(common,p.worldFromPortal);
        for (auto eye : *eyes) {
            auto movedEye=transformPoint(common,eye); corners(moved,movedEye);
            auto before=portalFrustum(p,eye),after=portalFrustum(moved,movedEye);
            assert(before&&after); assert(close(before->left,after->left));
            assert(close(before->right,after->right)); assert(close(before->bottom,after->bottom));
            assert(close(before->top,after->top));
        }
    }
    auto app=initialPortal({{1,2,3},multiply(yaw,roll)},16./9.,4); assert(app);
    assert(close(app->heightMeters,.7)); assert(close(app->widthMeters,.7*16/9));
    auto up=rotate(app->worldFromPortal.orientation,{0,1,0}); assert(close(up.y,1));
    assert(close(app->worldFromPortal.position.y,1.9));
    auto center=app->worldFromPortal;
    assert(resizePortal(*app,.01)); assert(close(app->widthMeters,.5));
    assert(resizePortal(*app,100)); assert(close(app->widthMeters,10));
    assert(resetPortalDimensions(*app,16./9.)); assert(close(app->heightMeters,.7));
    assert(close(app->worldFromPortal.position.x,center.position.x));
    assert(close(app->worldFromPortal.orientation.y,center.orientation.y));
    assert(close(nearMetersToUnreal(.1,100),10));
}
