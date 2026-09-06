// Disposable integration probe of the actual UEVR PortalSession receiver.
// No renderer/status publication is simulated; output must remain unavailable.
#include "PortalSession.hpp"
#include <chrono>
#include <iostream>
#include <thread>

int main() {
    using namespace std::chrono_literals;
    uevrportal::PortalSession session;
    const auto end = std::chrono::steady_clock::now() + 12s;
    std::uint64_t lastSession{}, lastEpoch{}, lastSequence{};
    bool wasFresh = false;
    while (std::chrono::steady_clock::now() < end) {
        const auto state = session.latest();
        if (state && (state->sessionID != lastSession || state->trackingEpoch != lastEpoch || state->sequence != lastSequence)) {
            std::cout << "state," << state->sessionID << ',' << state->trackingEpoch << ',' << state->sequence << '\n';
            lastSession = state->sessionID; lastEpoch = state->trackingEpoch; lastSequence = state->sequence;
            wasFresh = true;
        } else if (!state && wasFresh) {
            std::cout << "stale," << lastSession << ',' << lastEpoch << ',' << lastSequence << '\n';
            wasFresh = false;
        }
        std::this_thread::sleep_for(5ms);
    }
    std::cout << "final_error," << session.error() << '\n';
    return lastSequence != 0 && !session.latest() ? 0 : 1;
}
