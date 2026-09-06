#include "portal/Protocol.hpp"
#include <cassert>
#include <fstream>
#include <iterator>
#include <limits>
#include <vector>
using namespace portal;
void geometryTests();
void protocolTests() {
    StateSnapshot s{1,1,1,1,1000000000,1000000000,true,{{0,0,2},{0,0,0,1}},{{0,0,0},{0,0,0,1}},2.4,1.35,.064};
    auto bytes=encodeState(s); auto decoded=decodeState(bytes); assert(decoded);
    std::ifstream file(PORTAL_FIXTURE_DIR "/state-v1.bin",std::ios::binary);
    std::vector<char> golden{std::istreambuf_iterator<char>(file),{}}; assert(golden.size()==200);
    for(size_t i=0;i<200;++i) assert(std::to_integer<unsigned char>(bytes[i])==static_cast<unsigned char>(golden[i]));
    for(size_t n=0;n<200;++n) assert(!decodeState(std::span(bytes).first(n)));
    std::vector<std::byte> large(bytes.begin(),bytes.end());large.push_back({}); assert(!decodeState(large));
    for(size_t offset : {size_t(0),size_t(4),size_t(6),size_t(56),size_t(60)}) {
        auto bad=bytes;bad[offset]=std::byte{255};assert(!decodeState(bad));
    }
    auto bad=s; bad.worldFromHead.position.x=std::numeric_limits<double>::quiet_NaN();
    bool threw=false;try { encodeState(bad); } catch(const std::invalid_argument&) { threw=true; } assert(threw);
    auto nan=bytes; for(size_t i=64;i<72;++i)nan[i]=std::byte{255}; assert(!decodeState(nan));
    auto zero=bytes; for(size_t i=88;i<120;++i)zero[i]=std::byte{0}; assert(!decodeState(zero));
    StateAcceptance a;auto now=StateAcceptance::Clock::now();assert(a.accept(s,now));
    assert(!a.accept(s,now));s.sequence=3;assert(a.accept(s,now));s.sequence=2;assert(!a.accept(s,now));
    s.sequence=4;s.trackingEpoch=2;assert(!a.accept(s,now));
    assert(!a.acceptReset({1,8,9}));assert(a.acceptReset({1,1,2}));assert(a.acceptReset({1,1,2}));
    assert(a.stale(now));assert(a.accept(s,now));s.trackingEpoch=1;s.sequence=5;assert(!a.accept(s,now));
    s.trackingEpoch=2;s.sessionID=2;assert(!a.accept(s,now));
    now+=std::chrono::milliseconds(251);s.sequence=1;s.trackingEpoch=1;assert(a.accept(s,now));
    s.sessionID=1;now+=std::chrono::milliseconds(251);assert(!a.accept(s,now));
    auto reset=encodeReset({2,1,2});assert(decodeReset(reset));reset[6]=std::byte{0};assert(!decodeReset(reset));
    StatusSnapshot status{1,2,3,4,5,true,"sbs","none"};
    auto json=encodeStatus(status);assert(decodeStatus(json));assert(json.ends_with('\n'));
    assert(!decodeStatus("{}\n"));assert(!decodeStatus(json+"x"));
    auto malformed=json;auto pos=malformed.find("\"sessionID\":\"1\"");assert(pos!=std::string::npos);
    malformed.replace(pos,15,"\"sessionID\":1");assert(!decodeStatus(malformed));
}
int main() { geometryTests(); protocolTests(); }
