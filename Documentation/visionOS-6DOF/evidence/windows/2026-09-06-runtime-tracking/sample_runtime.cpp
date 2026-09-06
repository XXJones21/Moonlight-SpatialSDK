#include <windows.h>
#include <openvr.h>
#include <cstdlib>
#include <cstdint>
#include <iomanip>
#include <iostream>

// Disposable read-only diagnostic: raw and standing poses, zero prediction.
int main(int argc, char** argv) {
    const double seconds = argc > 1 ? std::atof(argv[1]) : 16.;
    if (!(seconds > 0 && seconds <= 120)) return 9;
    auto dll = LoadLibraryW(L"D:\\Tools\\Moonlight-SpatialSDK\\External\\local-validation\\vrto3d-v5.0.0\\package\\vrto3d\\bin\\win64\\openvr_api.dll");
    if (!dll) return 10;
    auto init = reinterpret_cast<uint32_t (*)(vr::EVRInitError*, vr::EVRApplicationType, const char*)>(GetProcAddress(dll, "VR_InitInternal2"));
    auto get = reinterpret_cast<void* (*)(const char*, vr::EVRInitError*)>(GetProcAddress(dll, "VR_GetGenericInterface"));
    auto shutdown = reinterpret_cast<void (*)()>(GetProcAddress(dll, "VR_ShutdownInternal"));
    if (!init || !get || !shutdown) return 11;
    vr::EVRInitError error = vr::VRInitError_None;
    init(&error, vr::VRApplication_Background, nullptr);
    if (error != vr::VRInitError_None) { std::cerr << "init_error=" << error; return 12; }
    auto system = static_cast<vr::IVRSystem*>(get(vr::IVRSystem_Version, &error));
    if (!system || error != vr::VRInitError_None) { shutdown(); return 13; }
    LARGE_INTEGER frequency, start, now;
    QueryPerformanceFrequency(&frequency); QueryPerformanceCounter(&start);
    std::cout << "sampleNs,origin,connected,valid,result,m00,m01,m02,x,m10,m11,m12,y,m20,m21,m22,z\n" << std::setprecision(10);
    do {
        for (auto origin : {vr::TrackingUniverseRawAndUncalibrated, vr::TrackingUniverseStanding}) {
            vr::TrackedDevicePose_t poses[vr::k_unMaxTrackedDeviceCount]{};
            system->GetDeviceToAbsoluteTrackingPose(origin, 0.f, poses, vr::k_unMaxTrackedDeviceCount);
            QueryPerformanceCounter(&now);
            const int64_t ns = now.QuadPart / frequency.QuadPart * 1000000000LL +
                now.QuadPart % frequency.QuadPart * 1000000000LL / frequency.QuadPart;
            const auto& pose = poses[vr::k_unTrackedDeviceIndex_Hmd];
            std::cout << ns << ',' << origin << ',' << pose.bDeviceIsConnected << ',' << pose.bPoseIsValid << ',' << pose.eTrackingResult;
            for (const auto& row : pose.mDeviceToAbsoluteTracking.m) for (float v : row) std::cout << ',' << v;
            std::cout << '\n';
        }
        Sleep(5);
        QueryPerformanceCounter(&now);
    } while (double(now.QuadPart - start.QuadPart) / frequency.QuadPart < seconds);
    shutdown(); FreeLibrary(dll);
    return 0;
}
