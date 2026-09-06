#include "portal/Protocol.hpp"
#include <algorithm>
#include <bit>
#include <charconv>
#include <cmath>
#include <limits>
#include <map>

namespace portal {
namespace {
std::uint64_t readLE(std::span<const std::byte> b,std::size_t offset,std::size_t count) {
    std::uint64_t v=0;for(std::size_t i=0;i<count;++i)v|=std::uint64_t(std::to_integer<unsigned char>(b[offset+i]))<<(8*i);return v;
}
void writeLE(std::span<std::byte> b,std::size_t offset,std::uint64_t v,std::size_t count) {
    for(std::size_t i=0;i<count;++i)b[offset+i]=std::byte((v>>(8*i))&255);
}
bool header(std::span<const std::byte> b,std::string_view magic,std::size_t size) {
    if(b.size()!=size)return false;
    for(std::size_t i=0;i<4;++i)if(b[i]!=std::byte(magic[i]))return false;
    return readLE(b,4,2)==1&&readLE(b,6,2)==size;
}
void putHeader(std::span<std::byte> b,std::string_view magic) {
    for(std::size_t i=0;i<4;++i)b[i]=std::byte(magic[i]);writeLE(b,4,1,2);writeLE(b,6,b.size(),2);
}
bool sameGeometry(const StateSnapshot& a,const StateSnapshot& b) {
    const auto& x=a.worldFromPortal;const auto& y=b.worldFromPortal;
    return a.widthMeters==b.widthMeters&&a.heightMeters==b.heightMeters&&x.position.x==y.position.x&&
        x.position.y==y.position.y&&x.position.z==y.position.z&&x.orientation.x==y.orientation.x&&
        x.orientation.y==y.orientation.y&&x.orientation.z==y.orientation.z&&x.orientation.w==y.orientation.w;
}
bool resetValid(const ResetTransition& r) { return r.sessionID&&r.previousEpoch&&r.nextEpoch>r.previousEpoch; }
bool errorValid(std::string_view s) {
    return s=="none"||s=="staleTracking"||s=="invalidGeometry"||s=="unsupportedRuntime"||s=="outputUnavailable";
}
bool modeValid(std::string_view s) {
    // Extensible bounded identifier, never an arbitrary string requiring JSON escaping.
    return !s.empty()&&s.size()<=32&&std::all_of(s.begin(),s.end(),[](unsigned char c){return
        (c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-';});
}
// V1 is a flat object of ASCII names, integer/string identifiers and booleans. Reject duplicate
// keys, escapes, nested values and trailing data rather than accepting ambiguous status payloads.
class StatusParser {
public:
    explicit StatusParser(std::string_view input):s(input){}
    struct Value { std::string text;bool quoted; };
    std::optional<std::map<std::string,Value>> parse() {
        if(s.empty()||s.size()>MaxStatusBytes||s.back()!='\n')return {};
        std::map<std::string,Value> result;if(!take('{'))return {};
        do {
            auto key=quoted();if(!key||!take(':'))return {};
            ws();Value value{};
            if(i<s.size()&&s[i]=='"'){auto v=quoted();if(!v)return {};value={*v,true};}
            else {auto start=i;while(i<s.size()&&((s[i]>='a'&&s[i]<='z')||(s[i]>='0'&&s[i]<='9')))++i;
                if(start==i)return {};value={std::string(s.substr(start,i-start)),false};}
            if(!result.emplace(*key,std::move(value)).second||result.size()>9)return {};
            ws();if(i<s.size()&&s[i]=='}'){++i;ws();return i==s.size()?std::optional(result):std::nullopt;}
        }while(take(','));return {};
    }
private:
    std::string_view s;std::size_t i=0;
    void ws(){while(i<s.size()&&(s[i]==' '||s[i]=='\t'||s[i]=='\r'||s[i]=='\n'))++i;}
    bool take(char c){ws();if(i==s.size()||s[i]!=c)return false;++i;return true;}
    std::optional<std::string> quoted(){if(!take('"'))return {};auto start=i;
        while(i<s.size()&&s[i]!='"'){if(s[i]<32||s[i]>126||s[i]=='\\')return {};++i;}
        if(i==s.size())return {};auto value=std::string(s.substr(start,i-start));++i;return value;}
};
}
bool validState(const StateSnapshot& s) {
    return s.sessionID&&s.trackingEpoch&&s.sequence&&s.geometryRevision&&validTransform(s.worldFromHead)&&
        validTransform(s.worldFromPortal)&&std::isfinite(s.widthMeters)&&s.widthMeters>0&&
        std::isfinite(s.heightMeters)&&s.heightMeters>0&&std::isfinite(s.eyeSeparationMeters)&&
        s.eyeSeparationMeters>0&&s.eyeSeparationMeters<=.2;
}
std::optional<StateSnapshot> decodeState(std::span<const std::byte> b) {
    static_assert(sizeof(double)==8&&std::numeric_limits<double>::is_iec559);
    if(!header(b,"P6DV",StatePacketSize)||readLE(b,56,4)>1||readLE(b,60,4)!=0)return {};
    StateSnapshot s{};std::uint64_t* ids[]={&s.sessionID,&s.trackingEpoch,&s.sequence,&s.geometryRevision,&s.sampleTimeNs,&s.targetTimeNs};
    for(std::size_t i=0;i<6;++i)*ids[i]=readLE(b,8+8*i,8);s.trackingValid=readLE(b,56,4)==1;
    double* values[]={&s.worldFromHead.position.x,&s.worldFromHead.position.y,&s.worldFromHead.position.z,
        &s.worldFromHead.orientation.x,&s.worldFromHead.orientation.y,&s.worldFromHead.orientation.z,&s.worldFromHead.orientation.w,
        &s.worldFromPortal.position.x,&s.worldFromPortal.position.y,&s.worldFromPortal.position.z,
        &s.worldFromPortal.orientation.x,&s.worldFromPortal.orientation.y,&s.worldFromPortal.orientation.z,&s.worldFromPortal.orientation.w,
        &s.widthMeters,&s.heightMeters,&s.eyeSeparationMeters};
    for(std::size_t i=0;i<17;++i)*values[i]=std::bit_cast<double>(readLE(b,64+8*i,8));
    if(!validState(s))return {};return s;
}
std::array<std::byte,StatePacketSize> encodeState(const StateSnapshot& s) {
    if(!validState(s))throw std::invalid_argument("invalid state snapshot");
    std::array<std::byte,StatePacketSize> b{};putHeader(b,"P6DV");
    const std::uint64_t ids[]={s.sessionID,s.trackingEpoch,s.sequence,s.geometryRevision,s.sampleTimeNs,s.targetTimeNs};
    for(std::size_t i=0;i<6;++i)writeLE(b,8+8*i,ids[i],8);writeLE(b,56,s.trackingValid?1:0,4);
    const double values[]={s.worldFromHead.position.x,s.worldFromHead.position.y,s.worldFromHead.position.z,
        s.worldFromHead.orientation.x,s.worldFromHead.orientation.y,s.worldFromHead.orientation.z,s.worldFromHead.orientation.w,
        s.worldFromPortal.position.x,s.worldFromPortal.position.y,s.worldFromPortal.position.z,
        s.worldFromPortal.orientation.x,s.worldFromPortal.orientation.y,s.worldFromPortal.orientation.z,s.worldFromPortal.orientation.w,
        s.widthMeters,s.heightMeters,s.eyeSeparationMeters};
    for(std::size_t i=0;i<17;++i)writeLE(b,64+8*i,std::bit_cast<std::uint64_t>(values[i]),8);return b;
}
std::optional<ResetTransition> decodeReset(std::span<const std::byte> b) {
    if(!header(b,"P6DR",ResetPacketSize))return {};ResetTransition r{readLE(b,8,8),readLE(b,16,8),readLE(b,24,8)};
    if(!resetValid(r))return {};return r;
}
std::array<std::byte,ResetPacketSize> encodeReset(const ResetTransition& r) {
    if(!resetValid(r))throw std::invalid_argument("invalid epoch reset");std::array<std::byte,ResetPacketSize>b{};
    putHeader(b,"P6DR");writeLE(b,8,r.sessionID,8);writeLE(b,16,r.previousEpoch,8);writeLE(b,24,r.nextEpoch,8);return b;
}
bool StateAcceptance::leaseExpired(Clock::time_point now) const { return !latest_||now-received_>=TrackingLease; }
bool StateAcceptance::stale(Clock::time_point now) const {
    return leaseExpired(now)||!latest_->trackingValid||latest_->trackingEpoch!=epoch_;
}
bool StateAcceptance::accept(const StateSnapshot& s,Clock::time_point received) {
    if(!validState(s))return false;
    if(session_&&s.sessionID!=session_) {
        if(!leaseExpired(received)||retired_.size()>=64||std::find(retired_.begin(),retired_.end(),s.sessionID)!=retired_.end())return false;
        retired_.push_back(session_);latest_.reset();session_=0;lastReset_.reset();
    }
    if(session_) {
        if(s.trackingEpoch!=epoch_||s.sequence<=latest_->sequence||s.geometryRevision<latest_->geometryRevision)return false;
        // An epoch transition may relocate the origin; that must be reflected in a new geometry revision.
        if(s.geometryRevision==latest_->geometryRevision&&!sameGeometry(s,*latest_))return false;
    } else {session_=s.sessionID;epoch_=s.trackingEpoch;}
    latest_=s;received_=received;return true;
}
bool StateAcceptance::acceptReset(const ResetTransition& r) {
    if(!resetValid(r)||r.sessionID!=session_)return false;
    if(lastReset_&&r.previousEpoch==lastReset_->previousEpoch&&r.nextEpoch==lastReset_->nextEpoch&&r.nextEpoch==epoch_)return true;
    if(r.previousEpoch!=epoch_)return false;
    epoch_=r.nextEpoch;lastReset_=r;return true; // Keep old snapshot for ordering only; stale() invalidates it.
}
std::optional<StatusSnapshot> decodeStatus(std::string_view json) {
    auto object=StatusParser(json).parse();if(!object||object->size()!=9)return {};
    const std::array<std::string,9> names{"version","sessionID","trackingEpoch","acceptedSequence","geometryRevision","renderFrameID","trackingValid","outputMode","errorCode"};
    for(const auto& name:names)if(!object->contains(name))return {};
    auto& o=*object;if(o["version"].quoted||o["version"].text!="1")return {};
    StatusSnapshot s{};std::uint64_t* ids[]={&s.sessionID,&s.trackingEpoch,&s.acceptedSequence,&s.geometryRevision,&s.renderFrameID};
    for(std::size_t i=0;i<5;++i){auto& v=o[names[i+1]];
        if(!v.quoted||v.text.empty()||(v.text.size()>1&&v.text[0]=='0')||
           !std::all_of(v.text.begin(),v.text.end(),[](char c){return c>='0'&&c<='9';}))return {};
        auto [end,ec]=std::from_chars(v.text.data(),v.text.data()+v.text.size(),*ids[i]);
        if(ec!=std::errc{}||end!=v.text.data()+v.text.size())return {};}
    auto& valid=o["trackingValid"];if(valid.quoted||(valid.text!="true"&&valid.text!="false"))return {};
    s.trackingValid=valid.text=="true";s.outputMode=o["outputMode"].text;s.errorCode=o["errorCode"].text;
    if(!s.sessionID||!s.trackingEpoch||!o["outputMode"].quoted||!o["errorCode"].quoted||!modeValid(s.outputMode)||!errorValid(s.errorCode))return {};
    if(s.trackingValid&&s.errorCode!="none")return {};return s;
}
std::string encodeStatus(const StatusSnapshot& s) {
    if(!s.sessionID||!s.trackingEpoch||!modeValid(s.outputMode)||!errorValid(s.errorCode)||(s.trackingValid&&s.errorCode!="none"))
        throw std::invalid_argument("invalid status snapshot");
    return "{\"version\":1,\"sessionID\":\""+std::to_string(s.sessionID)+"\",\"trackingEpoch\":\""+std::to_string(s.trackingEpoch)+
        "\",\"acceptedSequence\":\""+std::to_string(s.acceptedSequence)+"\",\"geometryRevision\":\""+std::to_string(s.geometryRevision)+
        "\",\"renderFrameID\":\""+std::to_string(s.renderFrameID)+"\",\"trackingValid\":"+(s.trackingValid?"true":"false")+
        ",\"outputMode\":\""+s.outputMode+"\",\"errorCode\":\""+s.errorCode+"\"}\n";
}
bool statusMatches(const StatusSnapshot& s,const StateAcceptance& a) {
    return a.latest()&&s.sessionID==a.sessionID()&&s.trackingEpoch==a.trackingEpoch()&&
        s.acceptedSequence<=a.latest()->sequence&&s.geometryRevision==a.latest()->geometryRevision;
}
}
