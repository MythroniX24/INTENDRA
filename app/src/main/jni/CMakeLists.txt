# CMakeLists.txt — INTENDRA native backends
#
# Builds two independent shared libraries:
#   libtermux.so          — real PTY/forkpty terminal backend
#   libinterndra_llama.so — llama.cpp GGUF inference backend
#
# llama.cpp is fetched at configure time from an immutable commit. Keeping the
# dependency pinned makes the JNI wrapper reproducible while avoiding a huge
# vendored source tree in the Android project.

cmake_minimum_required(VERSION 3.22.1)
project("intendra-native" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android builds should use portable CPU kernels. GPU backends and OpenMP are
# intentionally disabled for this first production slice; they can be added as
# separate, device-capability-gated targets later.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# LLAMAFILE's ARM FP16 sgemm path is not portable to armeabi-v7a with
# Android NDK 27 (vld1q_f16/vld1_f16 are unavailable there). Use ggml's
# portable CPU kernels so every declared Android ABI can compile reliably.
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

# ── Real PTY terminal backend ─────────────────────────────────────────────
add_library(termux SHARED termux.c)
target_compile_options(termux PRIVATE -Wall -Wextra -O2)

# ── Pinned llama.cpp backend ──────────────────────────────────────────────
include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    # Pin an immutable commit. Do not use GIT_SHALLOW here: CMake/Git
    # cannot reliably shallow-clone an arbitrary commit hash on all runners.
    GIT_TAG        000547513f1530346ecd163db8b3e13962949961
)
FetchContent_MakeAvailable(llama_cpp)

add_library(interndra_llama SHARED interndra_llama.cpp)
target_compile_options(interndra_llama PRIVATE -O3 -ffast-math)
target_compile_definitions(interndra_llama PRIVATE INTERNDRA_LLAMA_PINNED=1)
target_link_libraries(interndra_llama PRIVATE llama log)

# llama's public target exports include/ and ggml/include/. Keep this explicit
# as well so the wrapper remains robust if upstream target visibility changes.
target_include_directories(interndra_llama PRIVATE
    "${llama_cpp_SOURCE_DIR}/include"
    "${llama_cpp_SOURCE_DIR}/ggml/include"
)
