# CMakeLists.txt — NDK build for termux JNI library
#
# Builds libtermux.so from termux.c, providing:
#   - PTY subprocess creation (forkpty)
#   - Window resize (TIOCSWINSZ)
#   - Process wait/close
#
# The resulting .so is automatically placed into
# app/build/intermediates/.../jni/<abi>/libtermux.so
# and bundled into the APK.

cmake_minimum_required(VERSION 3.22.1)

# ── Project ──────────────────────────────────────────────────────────────
project("termux" C)

# ── C compiler flags ─────────────────────────────────────────────────────
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -std=gnu11 -O2")

# ── Build the shared library ─────────────────────────────────────────────
add_library(termux SHARED
    termux.c
)

# ── Link libraries ───────────────────────────────────────────────────────
# No extra libs needed — termux.c uses only POSIX/Android libc functions
# which are already available through the NDK's built-in libc.
# (log library not needed — no __android_log_print calls in termux.c)
# target_link_libraries(termux log)
