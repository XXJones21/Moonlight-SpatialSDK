// Offline production-helper WARP regression. No injection, network or pose traffic.
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
#include "PortalEyeCopy12.hpp"
#include "PortalUI12.hpp"

using Microsoft::WRL::ComPtr;
using uevrportal::PortalEyeSource12;
using uevrportal::PortalEyeCopy12;
unsigned failures{}, checks{};
void check(HRESULT hr) {
    if (FAILED(hr)) throw std::runtime_error("HRESULT " + std::to_string(hr));
}
void expect(bool ok, const char* why) {
    ++checks;
    if (!ok) { ++failures; std::cerr << "FAIL: " << why << '\n'; }
}
D3D12_RESOURCE_BARRIER barrier(ID3D12Resource* r, D3D12_RESOURCE_STATES a, D3D12_RESOURCE_STATES b) {
    D3D12_RESOURCE_BARRIER v{};
    v.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    v.Transition = {r, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES, a, b};
    return v;
}
ComPtr<ID3D12Resource> texture(ID3D12Device* device, UINT width, UINT height, DXGI_FORMAT format,
                             UINT16 arraySize = 1, UINT samples = 1) {
    D3D12_HEAP_PROPERTIES heap{};
    heap.Type = D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC desc{};
    desc.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    desc.Width = width; desc.Height = height; desc.DepthOrArraySize = arraySize; desc.MipLevels = 1;
    desc.SampleDesc.Count = samples; desc.Format = format;
    desc.Flags = D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
    ComPtr<ID3D12Resource> r;
    check(device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &desc,
          D3D12_RESOURCE_STATE_COPY_DEST, nullptr, IID_PPV_ARGS(&r)));
    return r;
}
ComPtr<ID3D12Resource> buffer(ID3D12Device* device, UINT64 bytes, D3D12_HEAP_TYPE type,
                            D3D12_RESOURCE_STATES state) {
    D3D12_HEAP_PROPERTIES heap{}; heap.Type = type;
    D3D12_RESOURCE_DESC desc{};
    desc.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER; desc.Width = bytes;
    desc.Height = 1; desc.DepthOrArraySize = desc.MipLevels = 1;
    desc.SampleDesc.Count = 1; desc.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    ComPtr<ID3D12Resource> r;
    check(device->CreateCommittedResource(&heap, D3D12_HEAP_FLAG_NONE, &desc, state, nullptr, IID_PPV_ARGS(&r)));
    return r;
}

void validation(ID3D12Device* device, IDXGIFactory4* factory) {
    auto left = texture(device, 16, 8, DXGI_FORMAT_R8G8B8A8_UNORM);
    auto right = texture(device, 16, 8, DXGI_FORMAT_R8G8B8A8_UNORM);
    const std::array<PortalEyeSource12, 2> valid{{
        {left.Get(), D3D12_RESOURCE_STATE_RENDER_TARGET, {2, 1, 0, 10, 5, 1}},
        {right.Get(), D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, {3, 2, 0, 11, 6, 1}}}};
    auto accepted = [&](const auto& eyes, UINT width = 8, UINT height = 4,
                        DXGI_FORMAT format = DXGI_FORMAT_R8G8B8A8_UNORM) {
        return PortalEyeCopy12::validate(device, eyes, width, height, format) == nullptr;
    };
    expect(accepted(valid), "valid distinct eye regions rejected");
    expect(PortalEyeCopy12::validate(nullptr, valid, 8, 4, DXGI_FORMAT_R8G8B8A8_UNORM) != nullptr,
           "null device accepted");
    expect(!accepted(valid, 0), "zero eye width accepted");
    expect(!accepted(valid, 8, 0), "zero eye height accepted");
    expect(!accepted(valid, 0x80000000u), "overflowing SBS width accepted");
    expect(!accepted(valid, 8, 4, DXGI_FORMAT_R16G16B16A16_FLOAT), "unsupported output format accepted");
    expect(!accepted(valid, 8, 4, DXGI_FORMAT_B8G8R8A8_UNORM), "RGBA to BGRA family mismatch accepted");
    for (unsigned eye = 0; eye < 2; ++eye) {
        auto invalid = valid; invalid[eye].resource = nullptr;
        expect(!accepted(invalid), "missing eye accepted");
        for (D3D12_BOX box : {D3D12_BOX{2, 1, 0, 2, 5, 1}, {10, 1, 0, 2, 5, 1},
                             {2, 1, 0, 10, 1, 1}, {2, 5, 0, 10, 1, 1},
                             {2, 1, 1, 10, 5, 2}, {2, 1, 0, 10, 5, 0},
                             {2, 1, 0, 9, 5, 1}, {2, 1, 0, 10, 4, 1},
                             {9, 1, 0, 17, 5, 1}, {2, 5, 0, 10, 9, 1}}) {
            invalid = valid; invalid[eye].box = box;
            expect(!accepted(invalid), "empty/reversed/depth/size/out-of-bounds eye box accepted");
        }
    }
    auto shared = valid; shared[1].resource = left.Get();
    expect(!accepted(shared), "same source with incompatible states accepted");
    shared[1].state = shared[0].state;
    expect(accepted(shared), "same source with matching states rejected");
    auto array = texture(device, 16, 8, DXGI_FORMAT_R8G8B8A8_UNORM, 2);
    auto msaa = texture(device, 16, 8, DXGI_FORMAT_R8G8B8A8_UNORM, 1, 4);
    auto wrongFormat = texture(device, 16, 8, DXGI_FORMAT_R16G16B16A16_FLOAT);
    auto buf = buffer(device, 256, D3D12_HEAP_TYPE_DEFAULT, D3D12_RESOURCE_STATE_COMMON);
    for (auto* unsupported : {array.Get(), msaa.Get(), wrongFormat.Get(), buf.Get()}) {
        auto invalid = valid; invalid[1].resource = unsupported;
        expect(!accepted(invalid), "unsupported source description accepted");
    }
    // D3D12 returns a singleton device per adapter; use a second adapter to obtain
    // a genuinely different device. This device is used only for validation.
    ComPtr<ID3D12Device> other;
    for (UINT index = 0; ; ++index) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(index, &adapter) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_ADAPTER_DESC1 desc{}; check(adapter->GetDesc1(&desc));
        if (!(desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) &&
            SUCCEEDED(D3D12CreateDevice(adapter.Get(), D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&other)))) break;
    }
    if (!other) throw std::runtime_error("other-device test requires a second D3D12 adapter");
    auto foreign = texture(other.Get(), 16, 8, DXGI_FORMAT_R8G8B8A8_UNORM);
    auto invalid = valid; invalid[1].resource = foreign.Get();
    expect(!accepted(invalid), "foreign-device eye accepted");
    std::cout << "Validation checks completed\n";
}

void pixels(ID3D12Device* device) {
    D3D12_COMMAND_QUEUE_DESC qd{};
    ComPtr<ID3D12CommandQueue> queue; check(device->CreateCommandQueue(&qd, IID_PPV_ARGS(&queue)));
    ComPtr<ID3D12CommandAllocator> allocator;
    check(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&allocator)));
    ComPtr<ID3D12GraphicsCommandList> cmd;
    check(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, allocator.Get(), nullptr, IID_PPV_ARGS(&cmd)));
    ComPtr<ID3D12Fence> fence; check(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)));
    HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!event) throw std::runtime_error("fence event creation failed");
    UINT64 serial{};
    unsigned cases{};
    const std::array<std::array<DXGI_FORMAT, 3>, 2> families{{
        {DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R8G8B8A8_TYPELESS, DXGI_FORMAT_R8G8B8A8_UNORM_SRGB},
        {DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_FORMAT_B8G8R8A8_TYPELESS, DXGI_FORMAT_B8G8R8A8_UNORM_SRGB}}};
    for (auto formats : families) for (unsigned variant = 0; variant < 3; ++variant)
    for (bool shared : {false, true}) for (unsigned iteration = 0; iteration < 3; ++iteration) {
        constexpr UINT eyeW = 8, h = 4, totalH = h + 16, pitch = 256;
        const auto outputFormat = formats[0];
        auto left = texture(device, 20, 8, formats[variant]);
        auto right = shared ? left : texture(device, 20, 8, formats[(variant + 1) % 3]);
        auto output = texture(device, eyeW * 2, totalH, outputFormat);
        // Includes COPY_SOURCE to cover the no-transition case.
        const auto leftState = iteration == 0 ? D3D12_RESOURCE_STATE_RENDER_TARGET :
            iteration == 1 ? D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE : D3D12_RESOURCE_STATE_COPY_SOURCE;
        const auto rightState = shared ? leftState : D3D12_RESOURCE_STATE_COPY_DEST;
        std::array<PortalEyeSource12, 2> eyes{{
            {left.Get(), leftState, {1, 2, 0, 9, 6, 1}},
            {right.Get(), rightState, {11, 1, 0, 19, 5, 1}}}};
        expect(PortalEyeCopy12::validate(device, eyes, eyeW, h, outputFormat) == nullptr,
               "compatible typed/typeless/sRGB eye formats rejected");
        std::vector<ComPtr<ID3D12Resource>> uploads;
        auto upload = [&](ID3D12Resource* r, const std::vector<UINT>& data, D3D12_RESOURCE_STATES state) {
            const auto d = r->GetDesc(); const UINT width = static_cast<UINT>(d.Width);
            auto u = buffer(device, pitch * d.Height, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
            void* ptr{}; D3D12_RANGE empty{}; check(u->Map(0, &empty, &ptr));
            for (UINT y = 0; y < d.Height; ++y) std::memcpy(static_cast<char*>(ptr) + y * pitch, data.data() + y * width, width * 4);
            u->Unmap(0, nullptr);
            D3D12_TEXTURE_COPY_LOCATION src{}; src.pResource = u.Get(); src.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
            src.PlacedFootprint.Footprint = {d.Format, width, d.Height, 1, pitch};
            D3D12_TEXTURE_COPY_LOCATION dst{}; dst.pResource = r;
            cmd->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
            if (state != D3D12_RESOURCE_STATE_COPY_DEST) {
                auto b = barrier(r, D3D12_RESOURCE_STATE_COPY_DEST, state); cmd->ResourceBarrier(1, &b);
            }
            uploads.push_back(u);
        };
        std::vector<UINT> lp(20 * 8, 0xff171717), rp(20 * 8, 0xff282828);
        for (UINT y = 0; y < h; ++y) for (UINT x = 0; x < eyeW; ++x) {
            lp[(y + 2) * 20 + x + 1] = 0xff000080u + x + (y << 8) + (iteration << 16);
            (shared ? lp : rp)[(y + 1) * 20 + x + 11] = 0xff800000u + (x << 8) + y + (iteration << 16);
        }
        upload(left.Get(), lp, leftState);
        if (!shared) upload(right.Get(), rp, rightState);
        upload(output.Get(), std::vector<UINT>(eyeW * 2 * totalH, 0xffa5a5a5), D3D12_RESOURCE_STATE_COPY_DEST);
        PortalEyeCopy12::record(cmd.Get(), output.Get(), eyes, eyeW);
        // Copy again after explicit transitions from the ORIGINAL source states.
        // Missing restoration or duplicate shared-source barriers trip the debug layer.
        std::vector<ComPtr<ID3D12Resource>> probes;
        for (unsigned eye = 0; eye < (shared ? 1u : 2u); ++eye) {
            const auto& source = eyes[eye];
            if (source.state != D3D12_RESOURCE_STATE_COPY_SOURCE) {
                auto b = barrier(source.resource, source.state, D3D12_RESOURCE_STATE_COPY_SOURCE); cmd->ResourceBarrier(1, &b);
            }
            auto probe = texture(device, 20, 8, source.resource->GetDesc().Format);
            cmd->CopyResource(probe.Get(), source.resource); probes.push_back(probe);
            if (source.state != D3D12_RESOURCE_STATE_COPY_SOURCE) {
                auto b = barrier(source.resource, D3D12_RESOURCE_STATE_COPY_SOURCE, source.state); cmd->ResourceBarrier(1, &b);
            }
        }
        auto b = barrier(output.Get(), D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_COPY_SOURCE); cmd->ResourceBarrier(1, &b);
        auto readback = buffer(device, pitch * totalH, D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
        D3D12_TEXTURE_COPY_LOCATION src{}; src.pResource = output.Get();
        D3D12_TEXTURE_COPY_LOCATION dst{}; dst.pResource = readback.Get(); dst.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
        dst.PlacedFootprint.Footprint = {outputFormat, eyeW * 2, totalH, 1, pitch};
        cmd->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
        check(cmd->Close()); ID3D12CommandList* lists[]{cmd.Get()}; queue->ExecuteCommandLists(1, lists);
        check(queue->Signal(fence.Get(), ++serial)); check(fence->SetEventOnCompletion(serial, event));
        if (WaitForSingleObject(event, 10000) != WAIT_OBJECT_0) throw std::runtime_error("GPU fence timeout");
        void* ptr{}; check(readback->Map(0, nullptr, &ptr));
        bool leftOk = true, rightOk = true, metadataOk = true;
        for (UINT y = 0; y < totalH; ++y) for (UINT x = 0; x < eyeW * 2; ++x) {
            const UINT actual = reinterpret_cast<UINT*>(static_cast<char*>(ptr) + y * pitch)[x];
            if (y >= h) metadataOk &= actual == 0xffa5a5a5;
            else if (x < eyeW) leftOk &= actual == 0xff000080u + x + (y << 8) + (iteration << 16);
            else rightOk &= actual == 0xff800000u + ((x - eyeW) << 8) + y + (iteration << 16);
        }
        readback->Unmap(0, nullptr);
        expect(leftOk, "left eye pixels misplaced or stale");
        expect(rightOk, "right eye pixels duplicated, misplaced or stale");
        expect(metadataOk, "eye copy overwrote metadata rows");
        ++cases; check(allocator->Reset()); check(cmd->Reset(allocator.Get(), nullptr));
    }
    check(cmd->Close()); CloseHandle(event);
    std::cout << cases << " WARP pixel frames (distinct/shared, RGBA/BGRA, typeless/sRGB, restored states)\n";
}

void combinedInFlight(ID3D12Device* device) {
    constexpr UINT eyeW = 16, h = 8, totalH = h + 16, pitch = 256;
    constexpr DXGI_FORMAT format = DXGI_FORMAT_R8G8B8A8_UNORM;
    struct Slot {
        ComPtr<ID3D12CommandAllocator> allocator;
        ComPtr<ID3D12GraphicsCommandList> command;
        std::array<ComPtr<ID3D12Resource>, 2> eyes;
        ComPtr<ID3D12Resource> target, readback;
        std::vector<ComPtr<ID3D12Resource>> uploads;
        uevrportal::PortalUI12 ui;
        UINT64 fenceValue{};
    };
    std::array<Slot, 3> slots;
    D3D12_COMMAND_QUEUE_DESC qd{};
    ComPtr<ID3D12CommandQueue> queue; check(device->CreateCommandQueue(&qd, IID_PPV_ARGS(&queue)));
    ComPtr<ID3D12Fence> fence; check(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence)));
    HANDLE event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!event) throw std::runtime_error("combined-copy fence event creation failed");
    // Literal per-frame results make swapped eyes, stale slots, wrong alpha and
    // menu ordering independently observable in the readback.
    constexpr std::array<UINT, 3> leftColors{0xff202020, 0xff404040, 0xff606060};
    constexpr std::array<UINT, 3> rightColors{0xff808080, 0xffa0a0a0, 0xffc0c0c0};
    constexpr std::array<UINT, 3> leftHud{0xff101090, 0xff2020a0, 0xff3030b0};
    constexpr std::array<UINT, 3> rightHud{0xff4040c0, 0xff5050d0, 0xff6060e0};
    for (UINT batch = 0; batch < 2; ++batch) {
        // Reuse allocators/compositors only after the preceding batch fence.
        // The second batch rotates content to expose stale source/descriptor reuse.
        for (UINT index = 0; index < slots.size(); ++index) {
            auto& slot = slots[index];
            if (slot.fenceValue) {
                expect(fence->GetCompletedValue() >= slot.fenceValue, "combined test reused an unfinished slot");
                slot.eyes = {}; slot.uploads.clear(); slot.target.Reset(); slot.readback.Reset();
                check(slot.allocator->Reset()); check(slot.command->Reset(slot.allocator.Get(), nullptr));
            } else {
                check(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&slot.allocator)));
                check(device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, slot.allocator.Get(), nullptr, IID_PPV_ARGS(&slot.command)));
            }
            const UINT color = (index + batch) % 3;
            auto left = texture(device, eyeW, h, format);
            auto right = texture(device, eyeW, h, format);
            auto hud = texture(device, eyeW, h, format);
            auto menu = texture(device, eyeW, h, format);
            slot.target = texture(device, eyeW * 2, totalH, format);
            auto* cmd = slot.command.Get();
            auto upload = [&](ID3D12Resource* resource, const std::vector<UINT>& pixels, D3D12_RESOURCE_STATES state) {
                const auto desc = resource->GetDesc(); const auto width = static_cast<UINT>(desc.Width);
                auto data = buffer(device, pitch * desc.Height, D3D12_HEAP_TYPE_UPLOAD, D3D12_RESOURCE_STATE_GENERIC_READ);
                void* mapped{}; D3D12_RANGE empty{}; check(data->Map(0, &empty, &mapped));
                for (UINT y = 0; y < desc.Height; ++y)
                    std::memcpy(static_cast<char*>(mapped) + y * pitch, pixels.data() + y * width, width * 4);
                data->Unmap(0, nullptr);
                D3D12_TEXTURE_COPY_LOCATION src{}; src.pResource = data.Get(); src.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
                src.PlacedFootprint.Footprint = {format, width, desc.Height, 1, pitch};
                D3D12_TEXTURE_COPY_LOCATION dst{}; dst.pResource = resource;
                cmd->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);
                if (state != D3D12_RESOURCE_STATE_COPY_DEST) {
                    auto b = barrier(resource, D3D12_RESOURCE_STATE_COPY_DEST, state); cmd->ResourceBarrier(1, &b);
                }
                slot.uploads.push_back(data);
            };
            upload(left.Get(), std::vector<UINT>(eyeW * h, leftColors[color]), D3D12_RESOURCE_STATE_RENDER_TARGET);
            upload(right.Get(), std::vector<UINT>(eyeW * h, rightColors[color]), D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
            upload(slot.target.Get(), std::vector<UINT>(eyeW * 2 * totalH, 0xffa5a5a5), D3D12_RESOURCE_STATE_COPY_DEST);
            std::vector<UINT> hudPixels(eyeW * h, 0), menuPixels(eyeW * h, 0);
            for (UINT y = 2; y < 6; ++y) for (UINT x = 2; x < 14; ++x) hudPixels[y * eyeW + x] = 0x80000080;
            for (UINT y = 2; y < 6; ++y) for (UINT x = 8; x < 14; ++x) menuPixels[y * eyeW + x] = 0xff00ff00;
            upload(hud.Get(), hudPixels, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
            upload(menu.Get(), menuPixels, D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE);
            std::array<PortalEyeSource12, 2> eyes{{
                {left.Get(), D3D12_RESOURCE_STATE_RENDER_TARGET, {0, 0, 0, eyeW, h, 1}},
                {right.Get(), D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE, {0, 0, 0, eyeW, h, 1}}}};
            const auto* diagnostic = PortalEyeCopy12::validate(device, eyes, eyeW, h, format);
            if (diagnostic) throw std::runtime_error(diagnostic);
            slot.eyes = {left, right};
            PortalEyeCopy12::record(cmd, slot.target.Get(), eyes, eyeW);
            auto b = barrier(slot.target.Get(), D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_RENDER_TARGET);
            cmd->ResourceBarrier(1, &b);
            if (!slot.ui.prepare(device, slot.target.Get(), {hud.Get(), menu.Get()}))
                throw std::runtime_error("combined eye-copy/UI prepare failed");
            slot.ui.draw(cmd, eyeW, h);
            b = barrier(slot.target.Get(), D3D12_RESOURCE_STATE_RENDER_TARGET, D3D12_RESOURCE_STATE_COPY_SOURCE);
            cmd->ResourceBarrier(1, &b);
            slot.readback = buffer(device, pitch * totalH, D3D12_HEAP_TYPE_READBACK, D3D12_RESOURCE_STATE_COPY_DEST);
            D3D12_TEXTURE_COPY_LOCATION src{}; src.pResource = slot.target.Get();
            D3D12_TEXTURE_COPY_LOCATION dst{}; dst.pResource = slot.readback.Get(); dst.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
            dst.PlacedFootprint.Footprint = {format, eyeW * 2, totalH, 1, pitch};
            cmd->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr); check(cmd->Close());
            // Drop caller references before submission. Slot refs retain the eye
            // resources, and production PortalUI12 retains its HUD/menu inputs.
            left.Reset(); right.Reset(); hud.Reset(); menu.Reset(); eyes = {};
            slot.fenceValue = batch * 3 + index + 1;
        }
        // Hold the queue at a CPU-signaled gate so all three submissions are
        // provably in flight with caller refs gone; fast WARP cannot race the check.
        struct Gate {
            ComPtr<ID3D12Fence> fence;
            ~Gate() { if (fence) fence->Signal(1); }
        } gate;
        check(device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&gate.fence)));
        check(queue->Wait(gate.fence.Get(), 1));
        for (auto& slot : slots) {
            ID3D12CommandList* lists[]{slot.command.Get()}; queue->ExecuteCommandLists(1, lists);
            check(queue->Signal(fence.Get(), slot.fenceValue));
        }
        for (const auto& slot : slots)
            expect(fence->GetCompletedValue() < slot.fenceValue, "combined batch did not retain three in-flight slots");
        check(gate.fence->Signal(1));
        check(fence->SetEventOnCompletion(slots.back().fenceValue, event));
        if (WaitForSingleObject(event, 10000) != WAIT_OBJECT_0) throw std::runtime_error("combined-copy GPU fence timeout");
        for (UINT index = 0; index < slots.size(); ++index) {
            const UINT color = (index + batch) % 3;
            auto& slot = slots[index]; void* mapped{}; check(slot.readback->Map(0, nullptr, &mapped));
            auto pixel = [&](UINT x, UINT y) { return reinterpret_cast<UINT*>(static_cast<char*>(mapped) + y * pitch)[x]; };
            expect(pixel(0, 0) == leftColors[color], "combined left scene changed behind transparent UI or used stale source");
            expect(pixel(16, 0) == rightColors[color], "combined right scene duplicated left eye or used stale source");
            expect(pixel(4, 3) == leftHud[color], "combined left HUD alpha incorrect");
            expect(pixel(20, 3) == rightHud[color], "combined right HUD alpha incorrect");
            expect(pixel(10, 3) == 0xff00ff00, "combined menu missing or behind HUD in left eye");
            expect(pixel(26, 3) == 0xff00ff00, "combined menu missing or behind HUD in right eye");
            bool metadataOk = true;
            for (UINT y = h; y < totalH; ++y) for (UINT x = 0; x < eyeW * 2; ++x) metadataOk &= pixel(x, y) == 0xffa5a5a5;
            expect(metadataOk, "combined copy/UI composition corrupted metadata rows");
            slot.readback->Unmap(0, nullptr);
        }
    }
    CloseHandle(event);
    std::cout << "6 combined eye-copy + HUD/menu frames; 2 gated batches of 3 in-flight slots, caller refs released, fenced reuse\n";
}

int main() { try {
    ComPtr<ID3D12Debug> debug; check(D3D12GetDebugInterface(IID_PPV_ARGS(&debug)));
    debug->EnableDebugLayer(); std::cout << "D3D12 debug layer: enabled\n";
    ComPtr<IDXGIFactory4> factory; check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)));
    ComPtr<IDXGIAdapter> warp; check(factory->EnumWarpAdapter(IID_PPV_ARGS(&warp)));
    ComPtr<ID3D12Device> device; check(D3D12CreateDevice(warp.Get(), D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device)));
    ComPtr<ID3D12InfoQueue> messages; check(device.As(&messages));
    validation(device.Get(), factory.Get()); pixels(device.Get()); combinedInFlight(device.Get());
    for (UINT64 i = 0; i < messages->GetNumStoredMessagesAllowedByRetrievalFilter(); ++i) {
        SIZE_T size{}; check(messages->GetMessage(i, nullptr, &size)); std::vector<char> storage(size);
        auto* message = reinterpret_cast<D3D12_MESSAGE*>(storage.data()); check(messages->GetMessage(i, message, &size));
        if (message->Severity <= D3D12_MESSAGE_SEVERITY_WARNING) expect(false, message->pDescription);
    }
    std::cout << (failures ? "FAIL " : "PASS ") << checks << " checks, " << failures << " failures\n";
    return failures ? 1 : 0;
} catch (const std::exception& e) { std::cerr << "FAIL: " << e.what() << '\n'; return 1; } }
