#include <winsock2.h>
#include <windows.h>
#include "portal_host/StateRelay.hpp"
#include <charconv>
#include <iostream>
#include <stdexcept>
#include <string_view>

namespace {
std::atomic_bool stop=false;
BOOL WINAPI control(DWORD event) {
    if(event==CTRL_C_EVENT||event==CTRL_BREAK_EVENT||event==CTRL_CLOSE_EVENT){stop.store(true);return TRUE;}return FALSE;
}
std::uint16_t port(std::string_view value) {
    unsigned number=0;auto [end,ec]=std::from_chars(value.data(),value.data()+value.size(),number);
    if(ec!=std::errc{}||end!=value.data()+value.size()||number==0||number>65535)throw std::invalid_argument("invalid UDP port");
    return static_cast<std::uint16_t>(number);
}
}
int main(int argc,char** argv) {
    try {
        portal_host::RelayConfig c;
        for(int i=1;i<argc;++i) {
            std::string_view key=argv[i];
            if(key=="--help") {std::cout<<"portal_host --listen 4243 --uevr-port 4244 --opentrack-port 4242 --peer 127.0.0.1 [--trace-poses]\n";return 0;}
            if(key=="--trace-poses"){c.tracePoses=true;continue;}
            if(++i>=argc)throw std::invalid_argument("missing argument value");std::string_view value=argv[i];
            if(key=="--listen")c.listenPort=port(value);
            else if(key=="--uevr-port")c.uevrPort=port(value);
            else if(key=="--opentrack-port")c.openTrackPort=port(value);
            else if(key=="--peer")c.peer=value;
            else throw std::invalid_argument("unknown argument");
        }
        if(c.listenPort==c.uevrPort||c.listenPort==c.openTrackPort||c.uevrPort==c.openTrackPort)
            throw std::invalid_argument("relay, UEVR and OpenTrack ports must differ");
        if(!SetConsoleCtrlHandler(control,TRUE))throw std::runtime_error("console handler registration failed");
        const int result=portal_host::runRelay(c,stop);SetConsoleCtrlHandler(control,FALSE);return result;
    } catch(const std::exception& e){std::cerr<<"portal_host: "<<e.what()<<'\n';return 1;}
}
