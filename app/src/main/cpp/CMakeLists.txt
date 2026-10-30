cmake_minimum_required(VERSION 3.22.1)

project("agentnative" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# llama.cpp release tag this bridge is written and tested against.
set(POCKETAGENT_LLAMA_TAG "b12017" CACHE STRING "llama.cpp git tag")
set(POCKETAGENT_LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp" CACHE PATH "Local llama.cpp checkout")

# llama.cpp build switches: static CPU backend, no tools, no network stack.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

if(DEFINED ANDROID_ABI)
    message(STATUS "PocketAgent native: Android ABI ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
        set(GGML_OPENMP ON CACHE BOOL "" FORCE)
    else()
        message(FATAL_ERROR "PocketAgent native supports arm64-v8a only, got ${ANDROID_ABI}")
    endif()
else()
    # Host build (Linux / Windows / macOS) used for JVM unit tests of the JNI bridge.
    set(GGML_NATIVE ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    find_package(JNI REQUIRED)
endif()

if(EXISTS "${POCKETAGENT_LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "PocketAgent native: using local llama.cpp at ${POCKETAGENT_LLAMA_DIR}")
    add_subdirectory("${POCKETAGENT_LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama-build")
else()
    message(STATUS "PocketAgent native: fetching llama.cpp ${POCKETAGENT_LLAMA_TAG}")
    include(FetchContent)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        ${POCKETAGENT_LLAMA_TAG}
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(agentnative SHARED llama_jni.cpp)

if(DEFINED ANDROID_ABI)
    find_library(log-lib log)
    target_link_libraries(agentnative PRIVATE llama ggml ${log-lib} android)
    # 16 KB page alignment required for Android 15+ devices.
    target_link_options(agentnative PRIVATE "-Wl,-z,max-page-size=16384")
else()
    target_include_directories(agentnative PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(agentnative PRIVATE llama ggml)
endif()

target_compile_options(agentnative PRIVATE -Wall -Wextra -Wno-unused-parameter)
