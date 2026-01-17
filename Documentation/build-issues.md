(base) PS D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK> ./gradlew installDebug

> Configure project :app
Could not get ndkDirectory from sdkComponents. Trying legacy path.
If the issue is not resolved, please make sure android.ndkVersion is set (and android.ndkPath if needed)
Could not get ndkDirectory from sdkComponents. Trying legacy path.
If the issue is not resolved, please make sure android.ndkVersion is set (and android.ndkPath if needed)

> Task :app:findLibraryTask
Error inspecting configuration debugAndroidTestCompileClasspath, full exception error: org.gradle.api.internal.artifacts.ivyservice.TypedResolveException: Could not resolve all files for configuration ':app:debugAndroidTestCompileClasspath'.

> Task :app:compileShadersFordebug FAILED
Error Compiling shader with command C:\Users\josh2\AppData\Local\Android\Sdk\ndk\29.0.14206865\shader-tools\windows-x86_64\glslc.exe -I D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\build\spatial\shaders -I D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\build\spatial\shaders\data\shaders -I D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders -DSHADER_FS=1 -DVERTEX_FORMAT_COMPACT_BATCH=1 D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders\bias_lighting_9slice.frag -o D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\build\generated\assets\compileShadersFordebug\bias_lighting_9slice.frag.spv.
glslc error: D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders\bias_lighting_9slice.frag:18: error: 'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan
D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders\bias_lighting_9slice.frag:20: error: 'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan
2 errors generated.


FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileShadersFordebug'.
> Error Compiling shader with command C:\Users\josh2\AppData\Local\Android\Sdk\ndk\29.0.14206865\shader-tools\windows-x86_64\glslc.exe -I D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\build\spatial\shaders -I D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\build\spatial\shaders\data\shaders -I D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders -DSHADER_FS=1 -DVERTEX_FORMAT_COMPACT_BATCH=1 D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders\bias_lighting_9slice.frag -o D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\build\generated\assets\compileShadersFordebug\bias_lighting_9slice.frag.spv. Error: D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders\bias_lighting_9slice.frag:18: error: 'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan
  D:\Tools\Moonlight-SpatialSDK\Moonlight-SpatialSDK\app\src\shaders\bias_lighting_9slice.frag:20: error: 'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan
  2 errors generated.


* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 2s
22 actionable tasks: 3 executed, 19 up-to-date