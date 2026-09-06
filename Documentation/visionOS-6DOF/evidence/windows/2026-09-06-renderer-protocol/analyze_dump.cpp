#include <windows.h>
#include <dbghelp.h>
#include <algorithm>
#include <cstring>
#include <iostream>
#include <vector>
#pragma comment(lib,"dbghelp.lib")
// Offline, local-symbol-only dump analysis. No game/debugger attachment.
struct Range { ULONG64 start,size; const char* data; };
std::vector<Range> ranges;
std::vector<Range> images;
DWORD64 CALLBACK imageBase(HANDLE,DWORD64 address){for(const auto& r:images)if(address>=r.start&&address-r.start<r.size)return r.start;return 0;}
PVOID CALLBACK functionEntry(HANDLE,DWORD64 address){
    for(const auto& r:images)if(address>=r.start&&address-r.start<r.size){
        auto dos=reinterpret_cast<const IMAGE_DOS_HEADER*>(r.data);
        auto nt=reinterpret_cast<const IMAGE_NT_HEADERS64*>(r.data+dos->e_lfanew);
        auto dir=nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_EXCEPTION];
        auto entries=reinterpret_cast<const RUNTIME_FUNCTION*>(r.data+dir.VirtualAddress);
        auto rva=address-r.start;
        for(unsigned i=0;i<dir.Size/sizeof(RUNTIME_FUNCTION);++i)if(rva>=entries[i].BeginAddress&&rva<entries[i].EndAddress)return const_cast<RUNTIME_FUNCTION*>(&entries[i]);
    }return nullptr;
}
BOOL CALLBACK readDump(HANDLE, DWORD64 address, PVOID buffer, DWORD size, LPDWORD read) {
    *read=0;
    for(const auto& r:ranges) if(address>=r.start && address-r.start<r.size) {
        const auto count=static_cast<DWORD>(std::min<ULONG64>(size,r.size-(address-r.start)));
        std::memcpy(buffer,r.data+(address-r.start),count);*read=count;return TRUE;
    }
    for(const auto& r:images) if(address>=r.start && address-r.start<r.size) {
        const auto count=static_cast<DWORD>(std::min<ULONG64>(size,r.size-(address-r.start)));
        __try { std::memcpy(buffer,r.data+(address-r.start),count);*read=count;return TRUE; }
        __except(EXCEPTION_EXECUTE_HANDLER) { return FALSE; }
    }
    return FALSE;
}
int wmain(int argc,wchar_t** argv){
    if(argc!=2)return 1;
    HANDLE file=CreateFileW(argv[1],GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,0,nullptr);
    if(file==INVALID_HANDLE_VALUE)return 2;
    HANDLE mapping=CreateFileMappingW(file,nullptr,PAGE_READONLY,0,0,nullptr);
    auto base=static_cast<char*>(MapViewOfFile(mapping,FILE_MAP_READ,0,0,0));if(!base)return 3;
    auto stream=[&](ULONG type)->void*{PMINIDUMP_DIRECTORY dir{};void* data{};ULONG size{};return MiniDumpReadDumpStream(base,type,&dir,&data,&size)?data:nullptr;};
    if(auto list=static_cast<MINIDUMP_MEMORY_LIST*>(stream(MemoryListStream)))for(ULONG i=0;i<list->NumberOfMemoryRanges;++i){auto& r=list->MemoryRanges[i];ranges.push_back({r.StartOfMemoryRange,r.Memory.DataSize,base+r.Memory.Rva});}
    if(auto list=static_cast<MINIDUMP_MEMORY64_LIST*>(stream(Memory64ListStream))){ULONG64 offset=list->BaseRva;for(ULONG64 i=0;i<list->NumberOfMemoryRanges;++i){auto& r=list->MemoryRanges[i];ranges.push_back({r.StartOfMemoryRange,r.DataSize,base+offset});offset+=r.DataSize;}}
    auto threads=static_cast<MINIDUMP_THREAD_LIST*>(stream(ThreadListStream));
    if(threads)for(ULONG i=0;i<threads->NumberOfThreads;++i){auto& r=threads->Threads[i].Stack;ranges.push_back({r.StartOfMemoryRange,r.Memory.DataSize,base+r.Memory.Rva});}
    HANDLE symbols=reinterpret_cast<HANDLE>(0x12345);
    SymSetOptions(SYMOPT_UNDNAME|SYMOPT_LOAD_LINES|SYMOPT_DEFERRED_LOADS|SYMOPT_FAIL_CRITICAL_ERRORS);
    if(!SymInitializeW(symbols,L"D:\\Tools\\Moonlight-SpatialSDK\\External\\local-validation\\UEVR-portal-0b59830",FALSE))return 4;
    if(auto modules=static_cast<MINIDUMP_MODULE_LIST*>(stream(ModuleListStream)))for(ULONG i=0;i<modules->NumberOfModules;++i){
        const auto& m=modules->Modules[i];auto name=reinterpret_cast<MINIDUMP_STRING*>(base+m.ModuleNameRva);
        std::wstring path(name->Buffer,name->Length/sizeof(wchar_t));
        SymLoadModuleExW(symbols,nullptr,path.c_str(),nullptr,m.BaseOfImage,m.SizeOfImage,nullptr,0);
        HANDLE imageFile=CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,0,nullptr);
        if(imageFile!=INVALID_HANDLE_VALUE){
            HANDLE imageMap=CreateFileMappingW(imageFile,nullptr,PAGE_READONLY|SEC_IMAGE_NO_EXECUTE,0,0,nullptr);
            if(imageMap){auto data=static_cast<const char*>(MapViewOfFile(imageMap,FILE_MAP_READ,0,0,0));if(data)images.push_back({m.BaseOfImage,m.SizeOfImage,data});CloseHandle(imageMap);}
            CloseHandle(imageFile);
        }
    }
    auto exception=static_cast<MINIDUMP_EXCEPTION_STREAM*>(stream(ExceptionStream));
    if(exception)std::cout<<"exception thread="<<exception->ThreadId<<" code="<<std::hex<<exception->ExceptionRecord.ExceptionCode<<" address="<<exception->ExceptionRecord.ExceptionAddress<<std::dec<<'\n';
    if(threads)for(ULONG i=0;i<threads->NumberOfThreads;++i){
        const auto& t=threads->Threads[i];CONTEXT ctx{};
        auto location=exception&&exception->ThreadId==t.ThreadId?exception->ThreadContext:t.ThreadContext;
        std::memcpy(&ctx,base+location.Rva,std::min<size_t>(sizeof(ctx),location.DataSize));
        std::cout<<"\nTHREAD "<<t.ThreadId<<(exception&&exception->ThreadId==t.ThreadId?" EXCEPTION":"")<<"\n";
        if(exception&&exception->ThreadId==t.ThreadId)std::cout<<"registers rcx="<<std::hex<<ctx.Rcx<<" rdx="<<ctx.Rdx<<" r8="<<ctx.R8<<" rsp="<<ctx.Rsp<<" readwrite="<<exception->ExceptionRecord.ExceptionInformation[0]<<" fault_address="<<exception->ExceptionRecord.ExceptionInformation[1]<<std::dec<<'\n';
        STACKFRAME64 frame{};frame.AddrPC={ctx.Rip,0,AddrModeFlat};frame.AddrStack={ctx.Rsp,0,AddrModeFlat};frame.AddrFrame={ctx.Rbp,0,AddrModeFlat};
        for(unsigned n=0;n<48 && frame.AddrPC.Offset;++n){
            const auto address=frame.AddrPC.Offset;char storage[sizeof(SYMBOL_INFO)+1024]{};auto symbol=reinterpret_cast<SYMBOL_INFO*>(storage);symbol->SizeOfStruct=sizeof(SYMBOL_INFO);symbol->MaxNameLen=1023;DWORD64 displacement{};
            IMAGEHLP_MODULE64 module{};module.SizeOfStruct=sizeof(module);SymGetModuleInfo64(symbols,address,&module);
            std::cout<<n<<" "<<std::hex<<address<<" "<<module.ModuleName<<"!";
            if(SymFromAddr(symbols,address,&displacement,symbol))std::cout<<symbol->Name<<"+"<<displacement;
            else std::cout<<"offset_"<<(address-module.BaseOfImage);
            IMAGEHLP_LINE64 line{};line.SizeOfStruct=sizeof(line);DWORD delta{};
            if(SymGetLineFromAddr64(symbols,address,&delta,&line))std::cout<<" "<<line.FileName<<":"<<std::dec<<line.LineNumber;
            std::cout<<std::dec<<'\n';
            if(!StackWalk64(IMAGE_FILE_MACHINE_AMD64,symbols,nullptr,&frame,&ctx,readDump,functionEntry,imageBase,nullptr))break;
        }
    }
    SymCleanup(symbols);UnmapViewOfFile(base);CloseHandle(mapping);CloseHandle(file);return 0;
}
