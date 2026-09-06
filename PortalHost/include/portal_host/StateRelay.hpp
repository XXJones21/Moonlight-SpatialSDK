#pragma once
#include <atomic>
#include <cstdint>
#include <string>
namespace portal_host {
struct RelayConfig {
    std::uint16_t listenPort=4243,uevrPort=4244,openTrackPort=4242;
    std::string peer="127.0.0.1";
    bool tracePoses=false;
};
int runRelay(const RelayConfig& config,const std::atomic_bool& stop);
}
