// Offline WARP pixel regression. No game injection, network, or pose traffic.
#include <d3d12.h>
#include <d3d12sdklayers.h>
#include <dxgi1_4.h>
#include <wrl/client.h>
#include <array>
#include <vector>
#include <iostream>
#include <stdexcept>
#include <string>
#include <cstring>
#include "PortalUI12.hpp"
using Microsoft::WRL::ComPtr;
void check(HRESULT hr){if(FAILED(hr))throw std::runtime_error("HRESULT " + std::to_string(hr));}
void expect(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
D3D12_RESOURCE_BARRIER barrier(ID3D12Resource* r,D3D12_RESOURCE_STATES a,D3D12_RESOURCE_STATES b){
    D3D12_RESOURCE_BARRIER v{};v.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    v.Transition={r,D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES,a,b};return v;
}
int main(){try{
    ComPtr<ID3D12Debug> debug;
    const bool debugEnabled=SUCCEEDED(D3D12GetDebugInterface(IID_PPV_ARGS(&debug)));
    if(debugEnabled)debug->EnableDebugLayer();
    std::cout<<"D3D12 debug layer: "<<(debugEnabled?"enabled":"unavailable")<<'\n';
    ComPtr<IDXGIFactory4> factory;check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)));
    ComPtr<IDXGIAdapter> warp;check(factory->EnumWarpAdapter(IID_PPV_ARGS(&warp)));
    ComPtr<ID3D12Device> device;check(D3D12CreateDevice(warp.Get(),D3D_FEATURE_LEVEL_11_0,IID_PPV_ARGS(&device)));
    ComPtr<ID3D12InfoQueue> messages;device.As(&messages);
    D3D12_COMMAND_QUEUE_DESC qd{};ComPtr<ID3D12CommandQueue> queue;check(device->CreateCommandQueue(&qd,IID_PPV_ARGS(&queue)));
    ComPtr<ID3D12CommandAllocator> allocator;check(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&allocator)));
    ComPtr<ID3D12GraphicsCommandList> cmd;check(device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,allocator.Get(),nullptr,IID_PPV_ARGS(&cmd)));
    ComPtr<ID3D12Fence> fence;check(device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&fence)));
    HANDLE event=CreateEventW(nullptr,FALSE,FALSE,nullptr);expect(event!=nullptr,"fence event");
    auto buffer=[&](UINT64 size,D3D12_HEAP_TYPE type,D3D12_RESOURCE_STATES state){
        D3D12_HEAP_PROPERTIES h{};h.Type=type;D3D12_RESOURCE_DESC d{};d.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;
        d.Width=size;d.Height=1;d.DepthOrArraySize=d.MipLevels=1;d.SampleDesc.Count=1;d.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
        ComPtr<ID3D12Resource> r;check(device->CreateCommittedResource(&h,D3D12_HEAP_FLAG_NONE,&d,state,nullptr,IID_PPV_ARGS(&r)));return r;
    };
    auto texture=[&](unsigned w,unsigned h,DXGI_FORMAT format,bool target){
        D3D12_HEAP_PROPERTIES hp{};hp.Type=D3D12_HEAP_TYPE_DEFAULT;D3D12_RESOURCE_DESC d{};
        d.Dimension=D3D12_RESOURCE_DIMENSION_TEXTURE2D;d.Width=w;d.Height=h;d.DepthOrArraySize=d.MipLevels=1;
        d.SampleDesc.Count=1;d.Format=format;d.Flags=target?D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET:D3D12_RESOURCE_FLAG_NONE;
        ComPtr<ID3D12Resource> r;check(device->CreateCommittedResource(&hp,D3D12_HEAP_FLAG_NONE,&d,D3D12_RESOURCE_STATE_COPY_DEST,nullptr,IID_PPV_ARGS(&r)));return r;
    };
    unsigned cases=0;UINT64 serial=0;
    // Exercise three reused frame slots, closed/reopened menus, absent HUD,
    // source-size changes, different output channel order and metadata bounds.
    for(auto format:{DXGI_FORMAT_R8G8B8A8_UNORM,DXGI_FORMAT_B8G8R8A8_UNORM}){
        std::array<uevrportal::PortalUI12,3> compositor;
        for(unsigned iteration=0;iteration<12;++iteration){
            const unsigned w=32,h=8,totalH=h+16,pitch=256;
            auto target=texture(w,totalH,format,true);
            const unsigned layerW=iteration<6?8:16,layerH=4;
            auto hud=texture(layerW,layerH,DXGI_FORMAT_R8G8B8A8_UNORM,false);
            auto menu=texture(layerW,layerH,DXGI_FORMAT_R8G8B8A8_UNORM,false);
            std::vector<ComPtr<ID3D12Resource>> uploads;
            auto upload=[&](ID3D12Resource* r, unsigned tw,unsigned th,const std::vector<unsigned>& pixels,D3D12_RESOURCE_STATES state){
                auto u=buffer(pitch*th,D3D12_HEAP_TYPE_UPLOAD,D3D12_RESOURCE_STATE_GENERIC_READ);
                void* ptr;D3D12_RANGE nr{0,0};check(u->Map(0,&nr,&ptr));
                for(unsigned y=0;y<th;++y)std::memcpy((char*)ptr+y*pitch,pixels.data()+y*tw,tw*4);u->Unmap(0,nullptr);
                D3D12_TEXTURE_COPY_LOCATION src{};src.pResource=u.Get();src.Type=D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
                src.PlacedFootprint.Footprint={r->GetDesc().Format,tw,th,1,pitch};
                D3D12_TEXTURE_COPY_LOCATION dst{};dst.pResource=r;cmd->CopyTextureRegion(&dst,0,0,0,&src,nullptr);
                auto b=barrier(r,D3D12_RESOURCE_STATE_COPY_DEST,state);cmd->ResourceBarrier(1,&b);uploads.push_back(u);
            };
            // Grey scene with a distinctive metadata region, valid for both formats.
            std::vector<unsigned> scene(w*totalH,0xff404040);
            for(unsigned y=h;y<totalH;++y)for(unsigned x=0;x<w;++x)scene[y*w+x]=0xffa5a5a5;
            upload(target.Get(),w,totalH,scene,D3D12_RESOURCE_STATE_RENDER_TARGET);
            std::vector<unsigned> hp(layerW*layerH,0),mp(layerW*layerH,0);
            // Premultiplied half-alpha red, with a transparent border.
            for(unsigned y=1;y<3;++y)for(unsigned x=2;x<layerW-2;++x)hp[y*layerW+x]=0x80000080;
            // Opaque green menu covering the right half, above HUD.
            for(unsigned y=1;y<3;++y)for(unsigned x=layerW/2;x<layerW-2;++x)mp[y*layerW+x]=0xff00ff00;
            upload(hud.Get(),layerW,layerH,hp,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
            upload(menu.Get(),layerW,layerH,mp,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
            const bool showHud=iteration%4!=3,showMenu=iteration%3!=1;
            auto& c=compositor[iteration%3];
            expect(c.prepare(device.Get(),target.Get(),{showHud?hud.Get():nullptr,showMenu?menu.Get():nullptr}),"prepare UI layers");
            c.draw(cmd.Get(),w/2,h);
            auto b=barrier(target.Get(),D3D12_RESOURCE_STATE_RENDER_TARGET,D3D12_RESOURCE_STATE_COPY_SOURCE);cmd->ResourceBarrier(1,&b);
            auto readback=buffer(pitch*totalH,D3D12_HEAP_TYPE_READBACK,D3D12_RESOURCE_STATE_COPY_DEST);
            D3D12_TEXTURE_COPY_LOCATION src{};src.pResource=target.Get();D3D12_TEXTURE_COPY_LOCATION dst{};dst.pResource=readback.Get();
            dst.Type=D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;dst.PlacedFootprint.Footprint={format,w,totalH,1,pitch};cmd->CopyTextureRegion(&dst,0,0,0,&src,nullptr);
            check(cmd->Close());ID3D12CommandList* lists[]={cmd.Get()};queue->ExecuteCommandLists(1,lists);
            check(queue->Signal(fence.Get(),++serial));check(fence->SetEventOnCompletion(serial,event));expect(WaitForSingleObject(event,10000)==WAIT_OBJECT_0,"GPU completion");
            void* ptr;check(readback->Map(0,nullptr,&ptr));
            auto pixel=[&](unsigned x,unsigned y){return *(unsigned*)((char*)ptr+y*pitch+x*4);};
            // Interior sample lies away from filtered edges for both aspect ratios.
            unsigned red=showHud?(format==DXGI_FORMAT_R8G8B8A8_UNORM?0xff2020a0:0xffa02020):0xff404040;
            if(pixel(6,3)!=red)std::cerr<<"frame "<<iteration<<" format "<<format<<" got "<<std::hex<<pixel(6,3)<<" expected "<<red<<std::dec<<'\n';
            expect(pixel(6,3)==red,"HUD absent or alpha incorrect in left eye");
            expect(pixel(22,3)==red,"HUD absent or alpha incorrect in right eye");
            expect(pixel(10,3)==(showMenu?0xff00ff00:red),"menu missing, stale, or wrong layer order");
            expect(pixel(26,3)==pixel(10,3),"menu differs between eyes");
            expect(pixel(0,0)==0xff404040,"transparent UI overwrote scene");
            for(unsigned y=h;y<totalH;++y)for(unsigned x=0;x<w;++x)expect(pixel(x,y)==0xffa5a5a5,"UI corrupted metadata rows");
            if(layerW==16)expect(pixel(7,0)==0xff404040,"UI aspect was stretched instead of fitted");
            readback->Unmap(0,nullptr);++cases;
            check(allocator->Reset());check(cmd->Reset(allocator.Get(),nullptr));
        }
    }
    cmd->Close();CloseHandle(event);
    if(messages)for(UINT64 i=0;i<messages->GetNumStoredMessagesAllowedByRetrievalFilter();++i){
        SIZE_T size=0;check(messages->GetMessage(i,nullptr,&size));std::vector<char> storage(size);
        auto message=reinterpret_cast<D3D12_MESSAGE*>(storage.data());check(messages->GetMessage(i,message,&size));
        if(message->Severity<=D3D12_MESSAGE_SEVERITY_ERROR)throw std::runtime_error(message->pDescription);
    }
    std::cout<<"PASS "<<cases<<" WARP UI composition frames\n";return 0;
}catch(const std::exception& e){std::cerr<<"FAIL: "<<e.what()<<'\n';return 1;}}
