#include "PortalNativePair.hpp"
#include <cstdlib>
#include <iostream>
using uevrportal::PortalNativePair;
static int checks;
static void check(bool ok, const char* why) { ++checks; if(!ok) { std::cerr << why << '\n'; std::exit(1); } }
static PortalNativePair views() { PortalNativePair p; p.view(0,100); p.view(1,200); return p; }
int main() {
    auto p=views();
    check(!p.matches(9,300,400),"views alone are not submitted images");
    check(p.begin(100,200),"distinct constructed views can begin");
    check(!p.matches(9,300,400),"begin alone is not a completed pair");
    p.complete(9,300,400);
    check(p.matches(9,300,400),"completed pair accepted");
    check(!p.matches(8,300,400),"stale frame rejected");
    check(!p.matches(9,300,401),"replacement right texture rejected");
    check(!p.matches(9,301,400),"replacement left texture rejected");
    check(!p.matches(9,400,300),"eye swap rejected");
    p.invalidate(); check(!p.matches(9,300,400),"reset rejects prior pair");
    PortalNativePair single; single.view(0,100);
    check(!single.begin(100,200),"startup one-view path rejected");
    single.complete(9,300,400); check(!single.matches(9,300,400),"completion cannot repair missing view");
    auto reversed=views(); check(!reversed.begin(200,100),"constructed eye order required");
    auto duplicate=views(); duplicate.view(1,100); check(!duplicate.begin(100,100),"same view cannot supply both eyes");
    auto repeat=views(); check(repeat.begin(100,200),"first begin"); repeat.complete(9,300,400);
    check(!repeat.begin(100,200),"second submission is ambiguous");
    check(!repeat.matches(9,300,400),"duplicate submission invalidates old evidence");
    auto missing=views(); missing.begin(100,200); missing.complete(9,300,0);
    check(!missing.matches(9,300,0),"null right rejected");
    auto alias=views(); alias.begin(100,200); alias.complete(9,300,300);
    check(!alias.matches(9,300,300),"native fix requires separate targets");
    auto changed=views(); changed.begin(100,200); changed.complete(9,300,400); changed.view(0,101);
    check(!changed.matches(9,300,400),"view reconstruction invalidates submitted pair");
    auto reuse=views(); reuse.begin(100,200); reuse.complete(9,300,400); reuse={};
    check(!reuse.matches(9,300,400),"slot reuse clears evidence");
    std::cout << checks << " native pair checks passed\n";
}
