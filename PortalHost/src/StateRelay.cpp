#include <winsock2.h>
#include <ws2tcpip.h>
#include "portal_host/StateRelay.hpp"
#include "portal_host/OpenTrackAdapter.hpp"
#include "portal/Protocol.hpp"
#include <algorithm>
#include <array>
#include <deque>
#include <iostream>
#include <optional>
#include <stdexcept>

namespace portal_host {
namespace {
using Clock=portal::StateAcceptance::Clock;
struct Winsock {
    Winsock(){WSADATA data{};if(WSAStartup(MAKEWORD(2,2),&data))throw std::runtime_error("WSAStartup failed");}
    ~Winsock(){WSACleanup();}
};
struct Socket {
    SOCKET value=INVALID_SOCKET;
    Socket(){value=socket(AF_INET,SOCK_DGRAM,IPPROTO_UDP);if(value==INVALID_SOCKET)throw std::runtime_error("UDP socket failed");}
    ~Socket(){if(value!=INVALID_SOCKET)closesocket(value);}
    Socket(const Socket&)=delete;Socket& operator=(const Socket&)=delete;
};
sockaddr_in endpoint(std::string_view ip,std::uint16_t port) {
    sockaddr_in a{};a.sin_family=AF_INET;a.sin_port=htons(port);std::string text(ip);
    if(inet_pton(AF_INET,text.c_str(),&a.sin_addr)!=1)throw std::invalid_argument("peer must be a numeric IPv4 address");return a;
}
bool sameEndpoint(const sockaddr_in& a,const sockaddr_in& b) {
    return a.sin_family==b.sin_family&&a.sin_addr.s_addr==b.sin_addr.s_addr&&a.sin_port==b.sin_port;
}
bool send(SOCKET socket,const sockaddr_in& to,const void* data,std::size_t size) {
    return sendto(socket,static_cast<const char*>(data),static_cast<int>(size),0,reinterpret_cast<const sockaddr*>(&to),sizeof(to))==static_cast<int>(size);
}
}
int runRelay(const RelayConfig& config,const std::atomic_bool& stop) {
    Winsock winsock;Socket socket;
    const auto allowed=endpoint(config.peer,0),uevr=endpoint("127.0.0.1",config.uevrPort),opentrack=endpoint("127.0.0.1",config.openTrackPort);
    const auto local=endpoint("0.0.0.0",config.listenPort);
    BOOL exclusive=TRUE;
    if(setsockopt(socket.value,SOL_SOCKET,SO_EXCLUSIVEADDRUSE,reinterpret_cast<const char*>(&exclusive),sizeof(exclusive))==SOCKET_ERROR)
        throw std::runtime_error("exclusive UDP binding failed");
    if(bind(socket.value,reinterpret_cast<const sockaddr*>(&local),sizeof(local))==SOCKET_ERROR)throw std::runtime_error("UDP bind failed");
    u_long nonblocking=1;if(ioctlsocket(socket.value,FIONBIO,&nonblocking)==SOCKET_ERROR)throw std::runtime_error("nonblocking UDP failed");
    portal::StateAcceptance acceptance;
    std::optional<sockaddr_in> client;
    std::optional<portal::StatusSnapshot> runtimeStatus;
    std::deque<portal::ResetTransition> pendingResets;
    constexpr std::size_t MaxPendingResets=16;
    std::uint64_t runtimeConfirmedEpoch=0;
    std::optional<std::array<std::byte,portal::StatePacketSize>> latestPacket;
    Clock::time_point lastResetSent{};
    Clock::time_point runtimeReceived{},lastStatusSent{};
    std::string lastError,lastMode;
    std::uint64_t lastRevision=0,lastEpoch=0,lastSession=0;
    bool lastValid=false,transportFailure=false;
    std::cout<<"portal_host bound "<<config.listenPort<<", peer "<<config.peer<<", UEVR 127.0.0.1:"<<config.uevrPort
             <<", OpenTrack 127.0.0.1:"<<config.openTrackPort<<'\n';
    while(!stop.load()) {
        fd_set ready;FD_ZERO(&ready);FD_SET(socket.value,&ready);timeval timeout{0,20000};
        if(select(0,&ready,nullptr,nullptr,&timeout)==SOCKET_ERROR)throw std::runtime_error("UDP select failed");
        std::optional<std::array<std::byte,portal::StatePacketSize>> newest;
        // Bound each drain so a flooded socket cannot starve stale/error reporting or shutdown.
        // Acceptance advances while draining, and only the newest accepted motion is forwarded.
        for(unsigned count=0;count<64&&!stop.load();++count) {
            std::array<std::byte,2048> buffer{};sockaddr_in from{};int fromSize=sizeof(from);
            const int n=recvfrom(socket.value,reinterpret_cast<char*>(buffer.data()),static_cast<int>(buffer.size()),0,
                                 reinterpret_cast<sockaddr*>(&from),&fromSize);
            if(n==SOCKET_ERROR){int error=WSAGetLastError();if(error==WSAEWOULDBLOCK)break;
                if(error==WSAEMSGSIZE||error==WSAECONNRESET)continue;throw std::runtime_error("UDP receive failed");}
            const auto received=Clock::now();auto bytes=std::span<const std::byte>(buffer.data(),static_cast<std::size_t>(n));
            if(sameEndpoint(from,uevr)) {
                auto status=portal::decodeStatus({reinterpret_cast<const char*>(buffer.data()),static_cast<std::size_t>(n)});
                if(!status)continue;
                // A runtime may acknowledge an intermediate link before reaching the latest host
                // epoch. Such status only releases reset controls; it never replaces client status.
                const bool acknowledgesQueuedEpoch=std::any_of(pendingResets.begin(),pendingResets.end(),
                    [&](const auto& reset){return reset.nextEpoch==status->trackingEpoch;});
                if(acceptance.latest()&&status->sessionID==acceptance.sessionID()&&
                   status->acceptedSequence<=acceptance.latest()->sequence&&
                   status->geometryRevision<=acceptance.latest()->geometryRevision&&
                   (acknowledgesQueuedEpoch||portal::statusMatches(*status,acceptance))) {
                    runtimeConfirmedEpoch=std::max(runtimeConfirmedEpoch,status->trackingEpoch);
                    while(!pendingResets.empty()&&pendingResets.front().nextEpoch<=runtimeConfirmedEpoch)
                        pendingResets.pop_front();
                }
                if(!portal::statusMatches(*status,acceptance))continue;
                if(runtimeStatus&&runtimeStatus->sessionID==status->sessionID&&runtimeStatus->trackingEpoch==status->trackingEpoch&&
                   (status->acceptedSequence<runtimeStatus->acceptedSequence||status->renderFrameID<runtimeStatus->renderFrameID))continue;
                runtimeStatus=std::move(status);runtimeReceived=received;
                continue;
            }
            if(from.sin_family!=AF_INET||from.sin_addr.s_addr!=allowed.sin_addr.s_addr)continue;
            if(auto reset=portal::decodeReset(bytes)) {
                const auto previousEpoch=acceptance.trackingEpoch();
                if(!client||!sameEndpoint(from,*client))continue;
                const bool alreadyQueued=std::any_of(pendingResets.begin(),pendingResets.end(),[&](const auto& pending){
                    return pending.sessionID==reset->sessionID&&pending.previousEpoch==reset->previousEpoch&&pending.nextEpoch==reset->nextEpoch;
                });
                const bool needsQueue=!alreadyQueued&&reset->nextEpoch>runtimeConfirmedEpoch;
                // Reserve bounded control capacity before mutating acceptance. Rejected transitions
                // leave the host epoch unchanged so the client can retry after UEVR catches up.
                if(needsQueue&&pendingResets.size()>=MaxPendingResets)continue;
                if(!acceptance.acceptReset(*reset))continue;
                if(needsQueue)pendingResets.push_back(*reset);
                // A reset is control, not motion: forward before the next state, including retransmissions.
                if(pendingResets.empty()) {
                    if(!send(socket.value,uevr,bytes.data(),bytes.size()))transportFailure=true;
                } else for(const auto& pending:pendingResets) {
                    auto control=portal::encodeReset(pending);
                    if(!send(socket.value,uevr,control.data(),control.size()))transportFailure=true;
                }
                lastResetSent=received;
                if(previousEpoch!=acceptance.trackingEpoch()){newest.reset();runtimeStatus.reset();}
                continue;
            }
            auto state=portal::decodeState(bytes);if(!state)continue;
            if(client&&!sameEndpoint(from,*client)&&(!acceptance.leaseExpired(received)||state->sessionID==acceptance.sessionID()))continue;
            const auto previousSession=acceptance.sessionID();
            if(!acceptance.accept(*state,received))continue;
            client=from;
            if(previousSession!=state->sessionID){runtimeStatus.reset();pendingResets.clear();runtimeConfirmedEpoch=0;}
            std::array<std::byte,portal::StatePacketSize> original{};std::copy(bytes.begin(),bytes.end(),original.begin());newest=original;
            latestPacket=original;
        }
        auto now=Clock::now();
        if(!pendingResets.empty()&&now-lastResetSent>=std::chrono::milliseconds(100)) {
            for(const auto& pending:pendingResets) {
                auto control=portal::encodeReset(pending);
                if(!send(socket.value,uevr,control.data(),control.size()))transportFailure=true;
            }
            lastResetSent=now;
            // Repair a state that reached UEVR before its reset. This is the single current snapshot,
            // never a motion queue, and resending does not refresh acceptance's receive-time lease.
            if(!newest&&latestPacket&&!acceptance.leaseExpired(now)&&
               acceptance.latest()->trackingEpoch==acceptance.trackingEpoch())
                if(!send(socket.value,uevr,latestPacket->data(),latestPacket->size()))transportFailure=true;
        }
        if(newest) {
            transportFailure=!send(socket.value,uevr,newest->data(),newest->size());
            if(!acceptance.stale(now))if(auto pose=toOpenTrack(acceptance.latest()->worldFromHead)) {
                auto packet=encodeOpenTrack(*pose);if(!send(socket.value,opentrack,packet.data(),packet.size()))transportFailure=true;
                if(config.tracePoses) {
                    const auto& s=*acceptance.latest();const auto& h=s.worldFromHead;
                    std::cout<<"pose "<<s.sequence<<" head_m="<<h.position.x<<','<<h.position.y<<','<<h.position.z
                        <<" head_xyzw="<<h.orientation.x<<','<<h.orientation.y<<','<<h.orientation.z<<','<<h.orientation.w<<" opentrack=";
                    for(double v:*pose)std::cout<<v<<',';std::cout<<'\n';
                }
            }
        }
        if(!client||!acceptance.latest())continue;
        const auto& s=*acceptance.latest();
        portal::StatusSnapshot status{s.sessionID,acceptance.trackingEpoch(),s.sequence,s.geometryRevision,0,false,"unavailable","outputUnavailable"};
        if(runtimeStatus&&now-runtimeReceived<portal::TrackingLease&&portal::statusMatches(*runtimeStatus,acceptance))status=*runtimeStatus;
        if(acceptance.stale(now)){status.trackingValid=false;status.errorCode="staleTracking";}
        else if(transportFailure){status.trackingValid=false;status.errorCode="outputUnavailable";}
        const bool changed=status.errorCode!=lastError||status.outputMode!=lastMode||status.geometryRevision!=lastRevision||
            status.trackingEpoch!=lastEpoch||status.sessionID!=lastSession||status.trackingValid!=lastValid;
        if(changed||now-lastStatusSent>=std::chrono::milliseconds(100)) {
            const auto json=portal::encodeStatus(status);
            if(send(socket.value,*client,json.data(),json.size())) {
                lastStatusSent=now;lastError=status.errorCode;lastMode=status.outputMode;lastRevision=status.geometryRevision;
                lastEpoch=status.trackingEpoch;lastSession=status.sessionID;lastValid=status.trackingValid;
            }
        }
    }
    std::cout<<"portal_host stopped; UDP socket closed by scope cleanup\n";return 0;
}
}
