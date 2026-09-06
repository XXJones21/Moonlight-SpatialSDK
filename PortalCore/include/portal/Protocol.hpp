#pragma once
#include "Geometry.hpp"
#include <chrono>
#include <cstddef>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace portal {
inline constexpr std::size_t StatePacketSize=200,ResetPacketSize=32,MaxStatusBytes=1024;
inline constexpr auto TrackingLease=std::chrono::milliseconds(250);
struct StateSnapshot {
    std::uint64_t sessionID,trackingEpoch,sequence,geometryRevision,sampleTimeNs,targetTimeNs;
    bool trackingValid;
    RigidTransform worldFromHead,worldFromPortal;
    double widthMeters,heightMeters,eyeSeparationMeters;
};
bool validState(const StateSnapshot& state);
std::optional<StateSnapshot> decodeState(std::span<const std::byte> bytes);
std::array<std::byte,StatePacketSize> encodeState(const StateSnapshot& state);
struct ResetTransition { std::uint64_t sessionID,previousEpoch,nextEpoch; };
std::optional<ResetTransition> decodeReset(std::span<const std::byte> bytes);
std::array<std::byte,ResetPacketSize> encodeReset(const ResetTransition& reset);
// Transport must pin endpoint before calling this class. Parsing never changes acceptance state.
class StateAcceptance {
public:
    using Clock=std::chrono::steady_clock;
    bool accept(const StateSnapshot& state,Clock::time_point received);
    bool acceptReset(const ResetTransition& reset);
    bool stale(Clock::time_point now) const;
    bool leaseExpired(Clock::time_point now) const;
    const std::optional<StateSnapshot>& latest() const { return latest_; }
    std::uint64_t sessionID() const { return session_; }
    std::uint64_t trackingEpoch() const { return epoch_; }
private:
    std::optional<StateSnapshot> latest_;
    Clock::time_point received_{};
    std::uint64_t session_=0,epoch_=0;
    std::optional<ResetTransition> lastReset_;
    std::vector<std::uint64_t> retired_; // Bounded; fail closed after 64 session replacements.
};
struct StatusSnapshot {
    std::uint64_t sessionID,trackingEpoch,acceptedSequence,geometryRevision,renderFrameID;
    bool trackingValid;
    std::string outputMode,errorCode;
};
std::optional<StatusSnapshot> decodeStatus(std::string_view json);
std::string encodeStatus(const StatusSnapshot& status);
bool statusMatches(const StatusSnapshot& status,const StateAcceptance& acceptance);
}
