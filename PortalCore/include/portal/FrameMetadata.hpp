#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>

namespace portal {
inline constexpr unsigned FrameMetadataBytes=44, MetadataStripHeight=16, MetadataRowBits=176;
struct FrameMetadata {
    bool valid{};
    std::uint64_t sessionID{},trackingEpoch{},geometryRevision{},renderFrameID{};
};
inline std::uint32_t metadataCRC(std::span<const std::byte> bytes) {
    std::uint32_t crc=0xffffffffu;
    for(auto b:bytes) { crc^=std::to_integer<unsigned>(b); for(int i=0;i<8;++i) crc=(crc>>1)^(0xedb88320u & (0u-(crc&1u))); }
    return ~crc;
}
inline std::array<std::byte,FrameMetadataBytes> encodeFrameMetadata(const FrameMetadata& f) {
    std::array<std::byte,FrameMetadataBytes> out{};
    out[0]=std::byte{'P'};out[1]=std::byte{'6'};out[2]=std::byte{'F'};out[3]=std::byte{'M'};
    out[4]=std::byte{1};out[5]=std::byte{static_cast<unsigned char>(f.valid?1:0)};
    auto put=[&](unsigned offset,std::uint64_t v,unsigned n) {for(unsigned i=0;i<n;++i) out[offset+i]=std::byte((v>>(8*i))&255);};
    put(8,f.sessionID,8);put(16,f.trackingEpoch,8);put(24,f.geometryRevision,8);put(32,f.renderFrameID,8);
    put(40,metadataCRC(std::span{out}.first(40)),4);return out;
}
inline std::optional<FrameMetadata> decodeFrameMetadata(std::span<const std::byte> in) {
    if(in.size()!=44||in[0]!=std::byte{'P'}||in[1]!=std::byte{'6'}||in[2]!=std::byte{'F'}||in[3]!=std::byte{'M'}||
       in[4]!=std::byte{1}||(std::to_integer<unsigned>(in[5])&~1u)||in[6]!=std::byte{}||in[7]!=std::byte{})return {};
    auto get=[&](unsigned offset,unsigned n) {std::uint64_t v=0;for(unsigned i=0;i<n;++i)v|=std::uint64_t(std::to_integer<unsigned>(in[offset+i]))<<(8*i);return v;};
    if(get(40,4)!=metadataCRC(in.first(40)))return {};
    FrameMetadata f{in[5]==std::byte{1},get(8,8),get(16,8),get(24,8),get(32,8)};
    if(f.valid&&(!f.sessionID||!f.trackingEpoch||!f.geometryRevision))return {};
    return f;
}
// RGBA and BGRA black/white have identical byte layout. Alpha is always opaque.
inline std::uint32_t metadataPixel(const std::array<std::byte,44>& tag,unsigned eyeWidth,unsigned x,unsigned y) {
    const unsigned cell=eyeWidth/MetadataRowBits;
    if(!cell||y>=16||x>=cell*MetadataRowBits)return 0xff000000u;
    const unsigned bit=(y/8)*MetadataRowBits+x/cell;
    return (std::to_integer<unsigned>(tag[bit/8])&(1u<<(7-bit%8)))?0xffffffffu:0xff000000u;
}
}
