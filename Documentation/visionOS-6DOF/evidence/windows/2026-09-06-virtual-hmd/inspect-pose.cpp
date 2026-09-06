#include <windows.h>
#include <openvr.h>
#include <iostream>

// Disposable, read-only diagnostic. Uses the runtime DLL from the verified package.
int main() {
    const auto dll = LoadLibraryW(L"D:\\Tools\\Moonlight-SpatialSDK\\External\\local-validation\\vrto3d-v5.0.0\\package\\vrto3d\\bin\\win64\\openvr_api.dll");
    if (!dll) return 10;
    using Init = uint32_t (*)(vr::EVRInitError*, vr::EVRApplicationType, const char*);
    using Interface = void* (*)(const char*, vr::EVRInitError*);
    using Shutdown = void (*)();
    const auto init = reinterpret_cast<Init>(GetProcAddress(dll, "VR_InitInternal2"));
    const auto get = reinterpret_cast<Interface>(GetProcAddress(dll, "VR_GetGenericInterface"));
    const auto shutdown = reinterpret_cast<Shutdown>(GetProcAddress(dll, "VR_ShutdownInternal"));
    if (!init || !get || !shutdown) return 11;
    vr::EVRInitError error = vr::VRInitError_None;
    init(&error, vr::VRApplication_Background, nullptr);
    if (error != vr::VRInitError_None) { std::cerr << "init_error=" << error << '\n'; return 12; }
    auto* system = static_cast<vr::IVRSystem*>(get(vr::IVRSystem_Version, &error));
    if (!system || error != vr::VRInitError_None) { shutdown(); return 13; }
    bool valid = true;
    for (auto origin : {vr::TrackingUniverseRawAndUncalibrated, vr::TrackingUniverseStanding}) {
        vr::TrackedDevicePose_t poses[vr::k_unMaxTrackedDeviceCount]{};
        system->GetDeviceToAbsoluteTrackingPose(origin, 0.0f, poses, vr::k_unMaxTrackedDeviceCount);
        const auto& pose = poses[vr::k_unTrackedDeviceIndex_Hmd];
        const auto& matrix = pose.mDeviceToAbsoluteTracking;
        std::cout << "origin=" << origin << " connected=" << pose.bDeviceIsConnected
                  << " valid=" << pose.bPoseIsValid << " tracking_result=" << pose.eTrackingResult
                  << " position=" << matrix.m[0][3] << ',' << matrix.m[1][3] << ',' << matrix.m[2][3] << '\n';
        valid = valid && pose.bDeviceIsConnected && pose.bPoseIsValid && pose.eTrackingResult == vr::TrackingResult_Running_OK;
    }
    shutdown();
    FreeLibrary(dll);
    return valid ? 0 : 14;
}
