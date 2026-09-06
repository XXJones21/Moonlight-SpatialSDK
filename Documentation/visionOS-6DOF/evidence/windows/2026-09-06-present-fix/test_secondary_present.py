"""Exercise actual D3D12 secondary-swapchain dispatch without a game/GPU.

Only COM/hook-memory boundaries are stubbed. A recursion sentinel makes the
original failure bounded. Disk-byte restoration is simulated, not performed.
"""
from pathlib import Path
import subprocess
import sys

repo = Path(sys.argv[1]).resolve()
source = (repo/'External/UEVR-6DOF-Window/src/hooks/D3D12Hook.cpp').read_text()
start = source.index('HRESULT D3D12Hook::present_internal(')
end = source.index('\n    if (d3d12->m_is_phase_1) {\n        //d3d12->m_present_hook.reset();', start)
dispatch = source[start:end] + '\n    return 123; // main-game path outside this regression\n}\n'
scratch = repo/'External/local-validation/present-regression'
scratch.mkdir(exist_ok=True)
prefix = r'''
#include <windows.h>
#include <cstdint>
#include <optional>
#include <vector>
#include <iostream>
#include <stdexcept>
struct DXGI_PRESENT_PARAMETERS { int marker=42; };
struct IDXGISwapChain3 { int identity; HRESULT GetHwnd(HWND* out){*out=reinterpret_cast<HWND>(this);return S_OK;} };
using Fn=HRESULT(*)(IDXGISwapChain3*,UINT,UINT,DXGI_PRESENT_PARAMETERS*);
struct Instance { IDXGISwapChain3* value; operator IDXGISwapChain3*()const{return value;} void* deref()const{return value;} };
struct SwapHook { Instance instance; Instance get_instance(){return instance;} };
struct PointerHook { Fn original; template<class T>T get_original(){return reinterpret_cast<T>(original);} };
struct D3D12Hook {
    bool m_is_phase_1=false; PointerHook *m_present_hook,*m_present1_hook; SwapHook* m_swapchain_hook;
    static HRESULT present_internal(IDXGISwapChain3*,UINT,UINT,DXGI_PRESENT_PARAMETERS*,bool);
    static HRESULT WINAPI present(IDXGISwapChain3* s,UINT i,UINT f){return present_internal(s,i,f,nullptr,false);}
    static HRESULT WINAPI present1(IDXGISwapChain3* s,UINT i,UINT f,DXGI_PRESENT_PARAMETERS* p){return present_internal(s,i,f,p,true);}
};
D3D12Hook* g_d3d12_hook;
struct WindowFilter { static WindowFilter& get(){static WindowFilter f;return f;} bool filtered=true; bool is_filtered(HWND){return filtered;} };
namespace spdlog { template<class... T>void info(T...){ } template<class... T>void error(T...){ } }
bool restorationAvailable=false,restorationEffective=false,restored=false,protectionThrows=false;
int restoreWrites=0;
struct Address { template<class T>Address(T){} };
namespace utility { std::optional<std::vector<uint8_t>> get_original_bytes(Address){
    if(restorationAvailable)return std::vector<uint8_t>{0x90};return {}; } }
struct ProtectionOverride { template<class T>ProtectionOverride(T,size_t,DWORD){if(protectionThrows)throw std::runtime_error("VirtualProtect failed");} };
template<class T> void* fake_memcpy(T,const void*,size_t){++restoreWrites;restored=restorationEffective;return nullptr;}
#define memcpy fake_memcpy
'''
suffix = r'''
#undef memcpy
IDXGISwapChain3 game{0},secondary{1},other{2};
SwapHook swapHook{{&game}};
int calls=0,mode=0,failures=0,cases=0;
bool usePresent1=false;
DXGI_PRESENT_PARAMETERS parameters;
void check(bool ok,const char* text){if(!ok){++failures;std::cerr<<"FAIL: "<<text<<'\n';}}
HRESULT original(IDXGISwapChain3* s,UINT interval,UINT flags,DXGI_PRESENT_PARAMETERS* params){
    if(++calls>32)throw std::runtime_error("unbounded secondary Present recursion");
    check(interval==2&&flags==7,"Present arguments preserved");
    check(params==(usePresent1?&parameters:nullptr),"Present1 parameters preserved");
    if(mode==1&&!restored)return D3D12Hook::present_internal(s,interval,flags,params,usePresent1);
    if(mode==2&&s==&secondary)return D3D12Hook::present_internal(&other,interval,flags,params,usePresent1);
    return mode==3?E_FAIL:S_OK;
}
PointerHook pointerHook{original};
D3D12Hook hook{false,&pointerHook,&pointerHook,&swapHook};
HRESULT exercise(bool phase,bool recursion,bool available,bool effective,int selectedMode){
    ++cases;calls=0;restoreWrites=0;restored=false;
    restorationAvailable=available;restorationEffective=effective;mode=selectedMode;
    hook.m_is_phase_1=phase;WindowFilter::get().filtered=true;
    try{return D3D12Hook::present_internal(&secondary,2,7,usePresent1?&parameters:nullptr,usePresent1);}
    catch(const std::runtime_error& e){check(false,e.what());return E_UNEXPECTED;}
}
int main(){
    g_d3d12_hook=&hook;
    for(bool kind:{false,true})for(bool phase:{false,true}){
        usePresent1=kind;
        check(exercise(phase,false,false,false,0)==S_OK&&calls==1,"ordinary secondary Present succeeds once");
        check(exercise(phase,false,false,false,3)==E_FAIL,"original failure is propagated");
        check(exercise(phase,true,true,true,1)==S_OK&&calls==2&&restoreWrites==1,"recoverable overlay loop restores and presents");
        check(exercise(phase,true,false,false,1)==DXGI_ERROR_INVALID_CALL&&calls==1,"unrecoverable loop fails without false success");
        check(exercise(phase,true,true,false,1)==DXGI_ERROR_INVALID_CALL&&calls==2,"ineffective restoration has bounded retry");
        protectionThrows=true;
        check(exercise(phase,true,true,true,1)==DXGI_ERROR_INVALID_CALL&&calls==1&&restoreWrites==0,"protection failure returns HRESULT instead of escaping Present");
        protectionThrows=false;
        check(exercise(phase,false,false,false,0)==S_OK&&calls==1,"guard state clears after failure");
        check(exercise(phase,false,false,false,2)==S_OK&&calls==2&&restoreWrites==0,"different swapchains may nest without repair");
    }
    hook.m_is_phase_1=false;calls=0;
    check(D3D12Hook::present_internal(&game,2,7,nullptr,false)==123&&calls==0,"game swapchain stays on existing main path");
    hook.m_is_phase_1=true;WindowFilter::get().filtered=false;
    check(D3D12Hook::present_internal(&secondary,2,7,nullptr,false)==123&&calls==0,"unfiltered first swapchain retains initialization path");
    if(failures){std::cerr<<failures<<" failed checks\n";return 1;}
    std::cout<<"PASS: "<<cases<<" secondary dispatch cases plus main-path preservation\n";
}
'''
cpp=scratch/'secondary_present.cpp'
cpp.write_text(prefix+dispatch+suffix)
exe=scratch/'secondary_present.exe'
subprocess.run(['cl','/nologo','/EHsc','/std:c++17','/MD',str(cpp),f'/Fe:{exe}',f'/Fo:{scratch/"secondary_present.obj"}'],check=True)
raise SystemExit(subprocess.run([str(exe)]).returncode)
