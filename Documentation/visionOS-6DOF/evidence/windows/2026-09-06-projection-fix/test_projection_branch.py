"""Compile the real portal projection branch with narrow engine-boundary stubs.

Poison output to reproduce the no-original-hook / missing-state path, then check
all matrix entries, precision bounds and valid-only eye observation. Requires cl
in PATH. No game process, renderer, or runtime is touched.
"""
from pathlib import Path
import subprocess
import sys

repo = Path(sys.argv[1]).resolve()
source = (repo/'External/UEVR-6DOF-Window/src/mods/vr/FFakeStereoRenderingHook.cpp').read_text()
start = source.index('if(WindowMode::get()->portal_output_enabled()) {', source.index('auto& double_matrix = *(Matrix4x4d*)out;', source.index('__forceinline Matrix4x4f* FFakeStereoRenderingHook::calculate_stereo_projection_matrix')))
opening = source.index('{', start)
depth = 1; end = opening + 1
while depth:
    if source[end] == '{': depth += 1
    if source[end] == '}': depth -= 1
    end += 1
branch = source[start:end]
scratch = repo/'External/local-validation/projection-regression'
scratch.mkdir(exist_ok=True)
prefix = r'''
#include <portal/Geometry.hpp>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <memory>
template<class T> struct Matrix { T value[4][4]; T* operator[](unsigned i){return value[i];} };
using Matrix4x4f=Matrix<float>; using Matrix4x4d=Matrix<double>;
struct Hook { bool m_has_double_precision=false; } hook;
auto* g_hook=&hook;
struct VRStub { float m_nearz=0; } vrstub;
struct WindowMode { static WindowMode* get(){static WindowMode m;return &m;}
bool portal_output_enabled(){return true;} unsigned portal_eye_width(){return 1280;}
unsigned portal_eye_height(){return 720;} };
namespace sdk::globals { double get_near_clipping_plane(){return 10.;} }
namespace uevrportal {
struct PortalFrame { bool valid=false; unsigned gameFrameID=17;
std::array<portal::EyeFrustum,2> frusta{{{-.7,.5,-.3375,.3375},{-.5,.7,-.3375,.3375}}};
static inline unsigned observations=0;
static void observe(unsigned,unsigned,bool){++observations;} };
std::shared_ptr<PortalFrame> current;
auto gameFrame(){return current;}
}
Matrix4x4f* exercise(void* memory,int true_index){
auto* out=static_cast<Matrix4x4f*>(memory);auto* vr=&vrstub;
auto& double_matrix=*(Matrix4x4d*)out;
'''
suffix = r'''
return out; }
int failures=0;
void check(bool ok,const char* message){if(!ok){std::cerr<<"FAIL: "<<message<<'\n';++failures;}}
template<class T> void run(bool frameExists,bool valid,int eye,double nearValue){
    hook.m_has_double_precision=sizeof(T)==sizeof(double);
    uevrportal::current=frameExists?std::make_shared<uevrportal::PortalFrame>():nullptr;
    if(uevrportal::current)uevrportal::current->valid=valid;
    struct Guarded { Matrix<T> matrix; std::array<unsigned char,128> guard; } storage;
    storage.guard.fill(0xa5);
    for(auto& col:storage.matrix.value)for(auto& v:col)v=std::numeric_limits<T>::quiet_NaN();
    storage.matrix[3][2]=T(nearValue);
    uevrportal::PortalFrame::observations=0;
    exercise(&storage.matrix,eye);
    const bool accepted=frameExists&&valid&&eye>=0&&eye<2;
    const double saneNear=std::isfinite(nearValue)&&nearValue>0?nearValue:10.;
    const auto frustum=accepted?uevrportal::current->frusta[eye]:portal::EyeFrustum{-1,1,-.5625,.5625};
    const auto expected=portal::toUnrealReversedZ(frustum,saneNear);
    for(unsigned col=0;col<4;++col)for(unsigned row=0;row<4;++row)
        check(std::isfinite(storage.matrix[col][row])&&std::abs(storage.matrix[col][row]-expected[col*4+row])<1e-5,"fully initialized, correct projection");
    for(auto v:storage.guard)check(v==0xa5,"no writes beyond selected precision");
    check(uevrportal::PortalFrame::observations==unsigned(accepted),"only valid eye projections marked observed");
}
int main(){
    for(int scenario=0;scenario<3;++scenario)for(int eye: {0,1,-1,2})for(double nearValue:{10.,0.,std::numeric_limits<double>::quiet_NaN()}){
        run<float>(scenario!=0,scenario==2,eye,nearValue);
        run<double>(scenario!=0,scenario==2,eye,nearValue);
    }
    if(failures){std::cerr<<failures<<" checks failed\n";return 1;}
    std::cout<<"PASS: 72 float/double projection cases, poison overwrite, bounds, and valid-only observation\n";
}
'''
cpp = scratch/'projection_branch.cpp'
cpp.write_text(prefix+branch+suffix)
exe = scratch/'projection_branch.exe'
subprocess.run(['cl','/nologo','/EHsc','/std:c++17','/MD',f'/I{repo / "PortalCore/include"}',str(cpp),
    str(repo/'PortalCore/build/Release/portal_core.lib'), f'/Fe:{exe}',f'/Fo:{scratch / "projection_branch.obj"}'],check=True)
raise SystemExit(subprocess.run([str(exe)]).returncode)
