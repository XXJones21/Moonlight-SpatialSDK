"""Run actual exporter lifecycle branches with deterministic COM/GPU boundaries."""
from pathlib import Path
import subprocess
import sys

repo=Path(sys.argv[1]).resolve()
source=(repo/'External/UEVR-6DOF-Window/src/mods/portal/PortalOutput.cpp').read_text()
methods=source[source.index('    bool idle12()'):source.index('    void show()')]
start=source.index('    ComPtr<ID3D12Device> device;',source.index('void PortalOutput::d3d12'))
dispatch=source[start:source.index('    slot.source=source;',start)]
tail_start=source.index('    const auto presented=o.swap->Present',start)
tail=source[tail_start:source.index('\n}',tail_start)]
scratch=repo/'External/local-validation/output-lifetime-regression'
scratch.mkdir(exist_ok=True)
prefix=r'''
#include <array>
#include <cstdint>
#include <iostream>
#include <string>
#include <type_traits>
#include <vector>
using HRESULT=int; constexpr HRESULT S_OK=0,E_FAIL=-1;
#define FAILED(v) ((v)<0)
#define IID_PPV_ARGS(v) v
using HWND=void*;
using DXGI_FORMAT=int;
constexpr int DXGI_FORMAT_UNKNOWN=0,DXGI_USAGE_RENDER_TARGET_OUTPUT=1,DXGI_SWAP_EFFECT_FLIP_DISCARD=1,DXGI_SCALING_NONE=1,DXGI_ALPHA_MODE_IGNORE=1,DXGI_MWA_NO_ALT_ENTER=1,D3D12_FENCE_FLAG_NONE=0;
template<class T>struct ComPtr {
    T* p=nullptr; ComPtr()=default; ComPtr(T* value):p(value){}
    T* Get()const{return p;} T* operator->()const{return p;} explicit operator bool()const{return p!=nullptr;}
    T** operator&(){p=nullptr;return &p;} void Reset(){p=nullptr;}
    ComPtr& operator=(T* value){p=value;return *this;}
    template<class U>HRESULT As(ComPtr<U>* out){return p->QueryInterface(&out->p);}
    template<class U>HRESULT As(U** out){return p->QueryInterface(out);}
};
struct IUnknown {
    virtual ~IUnknown()=default;
    IUnknown* canonical=this;
    template<class T>HRESULT QueryInterface(T** out){
        if constexpr(std::is_same_v<T,IUnknown>)*out=canonical;
        else *out=dynamic_cast<T*>(this);
        return *out?S_OK:E_FAIL;
    }
};
int fencesCreated=0,swapsCreated=0;
struct ID3D12Fence:IUnknown {uint64_t completed=0;uint64_t GetCompletedValue(){return completed;}};
struct ID3D12Device:IUnknown {
    IUnknown identity;
    explicit ID3D12Device(bool distinct=true){if(distinct)canonical=&identity;}
    HRESULT CreateFence(uint64_t initial,int,ID3D12Fence** out){++fencesCreated;*out=new ID3D12Fence;(*out)->completed=initial;return S_OK;}
};
struct ID3D12CommandQueue:IUnknown {
    bool completeImmediately=true; ID3D12Fence* pendingFence=nullptr;uint64_t pending=0;
    HRESULT Signal(ID3D12Fence* f,uint64_t value){pendingFence=f;pending=value;if(completeImmediately)f->completed=value;return S_OK;}
    void finish(){if(pendingFence)pendingFence->completed=pending;}
};
struct IDXGISwapChain3:IUnknown {
    unsigned index=0;
    unsigned GetCurrentBackBufferIndex(){return index;}
    HRESULT Present(unsigned,unsigned){index=(index+1)%3;return S_OK;}
};
using IDXGISwapChain1=IDXGISwapChain3;
struct DXGI_SWAP_CHAIN_DESC1 {unsigned Width{},Height{};DXGI_FORMAT Format{};struct{unsigned Count{};}SampleDesc;
    int BufferUsage{},BufferCount{},SwapEffect{},Scaling{},AlphaMode{};};
struct IDXGIFactory2 {
    HRESULT CreateSwapChainForHwnd(IUnknown*,HWND,DXGI_SWAP_CHAIN_DESC1*,void*,void*,IDXGISwapChain1** out){++swapsCreated;*out=new IDXGISwapChain1;return S_OK;}
    void MakeWindowAssociation(HWND,int){}
};
HRESULT CreateDXGIFactory1(IDXGIFactory2** out){*out=new IDXGIFactory2;return S_OK;}
struct Source {ID3D12Device* device;HRESULT GetDevice(ID3D12Device** out){*out=device;return S_OK;}};
struct Description {unsigned Width=2560,Height=720;};
struct Impl {
    HWND window=reinterpret_cast<void*>(1);
    ComPtr<IDXGISwapChain3> swap;ComPtr<IUnknown> identity,queueIdentity;
    unsigned width=0,height=0;DXGI_FORMAT format=0;bool twelve=false;
    struct Slot {uint64_t done=0;};std::array<Slot,3>slots11{},slots12{};
    ComPtr<ID3D12Fence> fence;ComPtr<ID3D12CommandQueue> queue;uint64_t serial=0;
    int displayed=0;std::vector<std::string> failures;
    bool idle11(){return true;}
    void show(){++displayed;}
    void failed(uint64_t,const char* why){failures.emplace_back(why);}
'''
middle=r'''
};
void report(uint64_t,void*,const char*){}
void frame(Impl& o,Source* source,ID3D12CommandQueue* queue,Description desc={}){
    const DXGI_FORMAT format=1;const uint64_t id=42;void* frame=nullptr;
'''
suffix=r'''
}
int failures=0;
void check(bool value,const char* why){if(!value){++failures;std::cerr<<"FAIL: "<<why<<'\n';}}
int main(){
    for(bool distinct:{false,true}){
        fencesCreated=0;swapsCreated=0;Impl output;ID3D12Device device(distinct);ID3D12CommandQueue queue;Source source{&device};
        for(int i=0;i<8;++i)frame(output,&source,&queue);
        check(output.displayed==8,"eight rotating-buffer frames display with stable device identity");
        check(fencesCreated==1&&swapsCreated==1,"stable identity retains exactly one fence and swapchain");
        check(output.serial==8&&output.fence->GetCompletedValue()==8,"fence and serial advance together");
        check(output.failures.empty(),"stable identity never stalls recreation or reuse");
        if(!output.failures.empty())for(auto& error:output.failures)std::cerr<<"  observed "<<error<<'\n';
        ID3D12Device deviceAlias;deviceAlias.canonical=device.canonical;Source aliasSource{&deviceAlias};
        ID3D12CommandQueue queueAlias;queueAlias.canonical=queue.canonical;
        frame(output,&aliasSource,&queueAlias);
        check(output.displayed==9&&fencesCreated==1&&swapsCreated==1,"different interface pointers with same canonical device/queue retain generation");
    }
    fencesCreated=0;swapsCreated=0;Impl output;ID3D12Device device;ID3D12CommandQueue queue;Source source{&device};
    queue.completeImmediately=false;frame(output,&source,&queue);
    auto* oldFence=output.fence.Get();auto* oldSwap=output.swap.Get();
    frame(output,&source,&queue,{3840,1080});
    check(output.fence.Get()==oldFence&&output.swap.Get()==oldSwap&&output.serial==1,"busy recreation preserves old resources and completion state");
    check(fencesCreated==1&&swapsCreated==1&&output.displayed==1,"busy recreation creates or displays nothing");
    queue.finish();queue.completeImmediately=true;frame(output,&source,&queue,{3840,1080});
    check(output.fence.Get()!=oldFence&&output.swap.Get()!=oldSwap&&output.serial==1,"completed resize replaces fence and resets serial together");
    check(output.displayed==2&&fencesCreated==2&&swapsCreated==2,"completed resize displays using one new resource generation");
    for(int i=0;i<7;++i)frame(output,&source,&queue,{3840,1080});
    check(output.displayed==9&&fencesCreated==2,"resized output survives repeated slot reuse");
    queue.completeImmediately=false;frame(output,&source,&queue,{3840,1080});
    oldFence=output.fence.Get();oldSwap=output.swap.Get();
    ID3D12CommandQueue replacementQueue;
    frame(output,&source,&replacementQueue,{3840,1080});
    check(output.fence.Get()==oldFence&&output.swap.Get()==oldSwap&&fencesCreated==2,"queue change waits on old generation before replacement");
    queue.finish();frame(output,&source,&replacementQueue,{3840,1080});
    check(output.queue.Get()==&replacementQueue&&output.serial==1&&fencesCreated==3,"queue change resets and signals one new generation");
    ID3D12Device replacementDevice;Source replacementSource{&replacementDevice};
    ID3D12CommandQueue replacementDeviceQueue;
    frame(output,&replacementSource,&replacementDeviceQueue,{3840,1080});
    check(output.identity.Get()==replacementDevice.canonical&&output.serial==1&&fencesCreated==4,"real device change replaces generation");
    if(failures){std::cerr<<failures<<" failed checks\n";return 1;}
    std::cout<<"PASS: canonical identity, eight-frame reuse, busy resize retention, and fence-generation reset\n";
}
'''
cpp=scratch/'output_lifetime.cpp'
cpp.write_text(prefix+methods+middle+dispatch+tail+suffix)
exe=scratch/'output_lifetime.exe'
subprocess.run(['cl','/nologo','/EHsc','/std:c++17','/MD',str(cpp),f'/Fe:{exe}',f'/Fo:{scratch/"output_lifetime.obj"}'],check=True)
raise SystemExit(subprocess.run([str(exe)]).returncode)
