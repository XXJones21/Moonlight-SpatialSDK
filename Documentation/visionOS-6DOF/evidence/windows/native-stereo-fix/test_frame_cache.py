"""Exercise actual PortalFrame.cpp with deterministic engine/session/COM boundaries."""
from pathlib import Path
import subprocess
import sys

repo = Path(sys.argv[1]).resolve()
portal = repo / 'External/UEVR-6DOF-Window/src/mods/portal'
body = (portal / 'PortalFrame.cpp').read_text()
body = body[body.index('namespace uevrportal {'):]
output = (portal / 'PortalOutput.cpp').read_text()
acceptance = output[output.index('std::shared_ptr<const PortalFrame> accepted('):output.index('// Deliberate synthetic fixture')]
body += '\nnamespace uevrportal {\n' + acceptance + '\n}\n'
prefix = r'''
#include "PortalFrame.hpp"
#include "PortalNativePair.hpp"
#include <atomic>
#include <iostream>
#include <cstdlib>
#include <chrono>
#include <cstring>
#include <portal/FrameMetadata.hpp>
namespace spdlog { template<class... T> void info(const char*,T&&...){} }
struct ID3D12Resource { int refs{}; } leftResource,rightResource,replacement;
namespace Microsoft::WRL {
template<class T> struct ComPtr {
    T* p{}; ComPtr()=default; ComPtr(T* v):p(v){if(p)++p->refs;}
    ComPtr(const ComPtr& v):ComPtr(v.p){}
    ComPtr& operator=(const ComPtr& v){ if(v.p)++v.p->refs; if(p)--p->refs; p=v.p; return *this; }
    ~ComPtr(){if(p)--p->refs;}
};
}
struct Framework {
    enum class RendererType { D3D11,D3D12 }; RendererType renderer=RendererType::D3D12;
    RendererType get_renderer_type()const{return renderer;}
} framework;
Framework* g_framework=&framework;
struct Runtime { bool openvr=true; bool is_openvr()const{return openvr;} } runtime;
struct VR {
    bool native=true,same=false,afr=false;
    static VR* get(){static VR v;return &v;}
    Runtime* get_runtime(){return &runtime;}
    bool is_native_stereo_fix_enabled()const{return native;}
    bool is_native_stereo_fix_same_pass_enabled()const{return same;}
    bool is_using_afr()const{return afr;}
    bool is_using_2d_screen()const{return false;}
    bool is_extreme_compatibility_mode_enabled()const{return false;}
    bool is_splitscreen_compatibility_enabled()const{return false;}
    bool is_sceneview_compatibility_enabled()const{return false;}
    bool is_stereo_emulation_enabled()const{return false;}
    bool is_roomscale_enabled()const{return false;}
    bool is_headlocked_aim_enabled()const{return false;}
    bool is_controller_aim_enabled()const{return false;}
    bool is_decoupled_pitch_enabled()const{return false;}
};
struct WindowMode {
    bool enabled=true;static WindowMode* get(){static WindowMode m;return &m;}
    bool portal_output_enabled()const{return enabled;}
};
namespace uevrportal {
struct PortalSession {
    std::optional<portal::StateSnapshot> state; bool live=true;
    portal::StatusSnapshot lastStatus{};
    void status(portal::StatusSnapshot s){lastStatus=std::move(s);}
    static PortalSession& get(){static PortalSession s;return s;}
    auto latest(){return state;}
    bool matchesLease(const portal::StateSnapshot& s)const{
        return live&&state&&s.sessionID==state->sessionID&&s.trackingEpoch==state->trackingEpoch;
    }
};
}
#define SPDLOG_DEBUG(...) ((void)0)
'''
tests = r'''
using uevrportal::PortalFrame;
int checks;
void check(bool ok,const char* why){++checks;if(!ok){std::cerr<<"FAIL "<<why<<'\n';std::exit(1);}}
void reset(){
    PortalFrame::reset();VR::get()->native=true;VR::get()->same=false;VR::get()->afr=false;
    framework.renderer=Framework::RendererType::D3D12;WindowMode::get()->enabled=true;
    auto& session=uevrportal::PortalSession::get();session.live=true;
    portal::StateSnapshot s{};s.sessionID=1;s.trackingEpoch=1;s.sequence=1;s.geometryRevision=1;s.trackingValid=true;
    s.worldFromHead={{0,0,0},{0,0,0,1}};s.worldFromPortal={{0,0,-2},{0,0,0,1}};
    s.widthMeters=2.4;s.heightMeters=1.35;s.eyeSeparationMeters=.064;session.state=s;
}
std::shared_ptr<const PortalFrame> prepare(unsigned id,uintptr_t a=100,uintptr_t b=200){
    const auto f=PortalFrame::latch(id,{{0,0,0},{0,0,0,1}});check(f&&f->valid,"valid latch");
    for(unsigned eye=0;eye<2;++eye){PortalFrame::observe(id,eye,false);PortalFrame::observe(id,eye,true);}
    PortalFrame::constructed(f,0,(void*)a);PortalFrame::constructed(f,1,(void*)b);return f;
}
void submit(std::shared_ptr<const PortalFrame> f,unsigned alias,uintptr_t a=100,uintptr_t b=200){
    check(PortalFrame::beginNativePair((void*)a,(void*)b)==f,"view identity resolves exact frame");
    PortalFrame::completeNativePair(f,alias,&leftResource,&rightResource);
}
uint64_t published(const std::shared_ptr<const PortalFrame>& f){
    const auto pixels=uevrportal::strip(1280,f.get());
    std::array<std::byte,44> tag{};
    for(unsigned bit=0;bit<352;++bit){
        const unsigned x=(bit%176)*7+3,y=(bit/176)*8+4;
        if(pixels[y*2560+x]==0xffffffffu)tag[bit/8]|=std::byte(1u<<(7-bit%8));
    }
    const auto decoded=portal::decodeFrameMetadata(tag);
    check(decoded&&decoded->valid,"actual metadata strip decodes with valid CRC");
    uevrportal::report(f->gameFrameID,f,"none");
    check(uevrportal::PortalSession::get().lastStatus.renderFrameID==decoded->renderFrameID,
        "UDP status and video carry the same publication identity");
    return decoded->renderFrameID;
}
int main(){
    // Observed live loading/menu transitions: the engine counter rolls backward
    // within the same headset session/epoch/revision while pose sequence advances.
    reset();uint64_t previousPublication=0;
    std::shared_ptr<const PortalFrame> previousFrame;
    for(unsigned engineID: {22386u,21u,22u,26055u,20699u}){
        auto transition=prepare(engineID);submit(transition,engineID+1);
        const auto publication=published(transition);
        check(publication>previousPublication,"scene counter rewind must not rewind wire frame identity");
        if(previousFrame)check(published(previousFrame)==previousPublication,
            "late old frame retains its lower identity after a newer frame is published");
        check(published(PortalFrame::latch(engineID,{{0,0,0},{0,0,0,1}}))==publication,
            "repeated latch cannot advertise old pixels as a new frame");
        previousPublication=publication;
        previousFrame=transition;
        ++uevrportal::PortalSession::get().state->sequence;
    }
    PortalFrame::reset();auto afterReset=prepare(21);submit(afterReset,22);
    check(published(afterReset)>previousPublication,"portal/device reset preserves wire monotonicity");
    reset();auto f=prepare(7);submit(f,108);
    check(PortalFrame::nativeFrame(108,&leftResource,&rightResource)==f,"render alias resolves immutable pose ID");
    check(uevrportal::accepted(7)==f,"live paired frame accepted");
    uevrportal::PortalSession::get().live=false;check(!uevrportal::accepted(7),"expired tracking lease suppresses frame");
    uevrportal::PortalSession::get().live=true;WindowMode::get()->enabled=false;
    check(!uevrportal::accepted(7),"disabled output suppresses existing frame");WindowMode::get()->enabled=true;
    VR::get()->native=false;check(!uevrportal::accepted(7),"mode mismatch suppressed before next latch");VR::get()->native=true;
    VR::get()->same=true;check(!uevrportal::accepted(7),"same-pass mismatch suppressed before next latch");VR::get()->same=false;
    uevrportal::PortalSession::get().state->trackingEpoch=2;check(!uevrportal::accepted(7),"new epoch suppresses prior frame before latch");
    uevrportal::PortalSession::get().state->trackingEpoch=1;
    check(!PortalFrame::nativeFrame(7,&leftResource,&rightResource),"game ID is not assumed to equal render alias");
    check(!PortalFrame::nativeFrame(108,&leftResource,&replacement),"recreated source rejected");
    check(leftResource.refs==1&&rightResource.refs==1,"cache pins both native sources");
    auto next=prepare(8); // Unreal may recycle view addresses while prior rendering is in flight.
    check(PortalFrame::nativeFrame(108,&leftResource,&rightResource)==f,"recycled view address preserves completed older frame");
    submit(next,109);check(PortalFrame::nativeFrame(109,&leftResource,&rightResource)==next,"recycled address binds new submission");
    check(PortalFrame::nativeFrame(108,&leftResource,&rightResource)==f,"new submission does not overwrite earlier alias");
    PortalFrame::reset();check(leftResource.refs==0&&rightResource.refs==0,"reset releases CPU pins");
    check(!PortalFrame::nativeFrame(108,&leftResource,&rightResource),"reset invalidates aliases");
    reset();f=prepare(7);submit(f,108);VR::get()->native=false;
    PortalFrame::latch(8,{{0,0,0},{0,0,0,1}});VR::get()->native=true;prepare(9,300,400);
    check(!PortalFrame::nativeFrame(108,&leftResource,&rightResource),"off/on mode transition rejects old native alias");
    reset();f=prepare(7);submit(f,108);VR::get()->same=true;prepare(8,300,400);
    check(!PortalFrame::nativeFrame(108,&leftResource,&rightResource),"same-pass transition clears old evidence");
    reset();f=prepare(7);submit(f,108);uevrportal::PortalSession::get().state->trackingEpoch=2;
    auto epoch=prepare(7,300,400);check(epoch!=f&&epoch->state.trackingEpoch==2,"same-ID epoch transition relatches");
    check(!PortalFrame::nativeFrame(108,&leftResource,&rightResource),"epoch clears aliases");
    submit(epoch,109,300,400);uevrportal::PortalSession::get().state->sessionID=2;
    auto session=prepare(7,500,600);check(session!=epoch&&session->state.sessionID==2,"same-ID new session relatches");
    reset();f=prepare(7);submit(f,108);auto evict=prepare(71,300,400);
    check(!PortalFrame::find(7)&&!PortalFrame::nativeFrame(108,&leftResource,&rightResource),"ring eviction rejects stale alias");
    PortalFrame::completeNativePair(f,110,&leftResource,&rightResource);
    check(!PortalFrame::nativeFrame(110,&leftResource,&rightResource),"late completion cannot fill reused slot");
    reset();f=prepare(7);submit(f,108);auto collision=prepare(8,300,400);submit(collision,108,300,400);
    check(!PortalFrame::nativeFrame(108,&leftResource,&rightResource),"ambiguous duplicate alias rejected");
    reset();auto a=prepare(7,100,200);auto b=prepare(8,300,400);
    check(!PortalFrame::beginNativePair((void*)100,(void*)400),"eyes from different portal states rejected");
    reset();framework.renderer=Framework::RendererType::D3D11;
    auto unsupported=PortalFrame::latch(7,{{0,0,0},{0,0,0,1}});
    check(!unsupported->valid&&unsupported->error=="unsupported_portal_configuration","D3D11 native fix stays rejected");
    framework.renderer=Framework::RendererType::D3D12;VR::get()->afr=true;
    unsupported=PortalFrame::latch(8,{{0,0,0},{0,0,0,1}});check(!unsupported->valid,"AFR stays rejected");
    PortalFrame::reset();std::cout<<checks<<" production frame-cache checks passed\n";
}
'''
scratch=repo/'External/local-validation/native-frame-cache'
scratch.mkdir(exist_ok=True)
source=scratch/'cache_test.cpp';source.write_text(prefix+body+tests)
exe=scratch/'cache_test.exe'
subprocess.run(['cl','/nologo','/EHsc','/std:c++20',f'/I{portal}',f'/I{repo / "PortalCore/include"}',str(source),str(repo/'PortalCore/src/Geometry.cpp'),f'/Fe:{exe}',f'/Fo:{scratch}/'],check=True)
subprocess.run([str(exe)],check=True)
