#include <windows.h>
#include <iostream>
#pragma comment(lib,"user32.lib")
int main(){
    DEVMODEW current{};current.dmSize=sizeof(current);
    if(!EnumDisplaySettingsW(nullptr,ENUM_CURRENT_SETTINGS,&current))return 1;
    unsigned count=0;bool listed=false;
    for(DWORD i=0;;++i){DEVMODEW mode{};mode.dmSize=sizeof(mode);if(!EnumDisplaySettingsW(nullptr,i,&mode))break;
        ++count;if(mode.dmPelsWidth==2560&&mode.dmPelsHeight==736&&mode.dmDisplayFrequency==60)listed=true;}
    DEVMODEW test=current;test.dmPelsWidth=2560;test.dmPelsHeight=736;test.dmDisplayFrequency=60;
    test.dmFields=DM_PELSWIDTH|DM_PELSHEIGHT|DM_DISPLAYFREQUENCY;
    const auto result=ChangeDisplaySettingsExW(nullptr,&test,nullptr,CDS_TEST,nullptr);
    std::cout<<"{\"currentWidth\":"<<current.dmPelsWidth<<",\"currentHeight\":"<<current.dmPelsHeight
        <<",\"currentHz\":"<<current.dmDisplayFrequency<<",\"enumeratedModes\":"<<count
        <<",\"requestedModeListed\":"<<(listed?"true":"false")<<",\"requestedModeTestResult\":"<<result
        <<",\"modeChanged\":false}\n";
}
