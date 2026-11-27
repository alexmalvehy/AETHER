# Automix's native code, from BitChord's app/src/main/cpp/CMakeLists.txt (its analysis and loop mix
# libraries only): the DSP analyser (tempo, beat grid, energy, structure), the Beat This! and
# open-unmix front ends, and the loop mix renderer on Signalsmith Stretch (MIT, third_party/).
cmake_minimum_required(VERSION 3.22.1)
project(aether_automix LANGUAGES C CXX)

set(AETHER_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/native")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(aether_analysis SHARED
    ${AETHER_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${AETHER_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${AETHER_NATIVE_DIR}/analyzer/resampler.cpp
    ${AETHER_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${AETHER_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)
target_include_directories(aether_analysis PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${AETHER_NATIVE_DIR})
target_compile_options(aether_analysis PRIVATE -O3 -fvisibility=hidden)
find_library(log-lib log)
target_link_libraries(aether_analysis ${log-lib})
# 16 KB pages (Android 15 and later devices built for them).
target_link_options(aether_analysis PRIVATE "-Wl,-z,max-page-size=16384")

add_library(aether_loopmix SHARED
    ${AETHER_NATIVE_DIR}/loopmix/loop_mix_renderer.cpp
    jni/loopmix_jni.cpp
)
target_include_directories(aether_loopmix PRIVATE ${AETHER_NATIVE_DIR} ${AETHER_NATIVE_DIR}/third_party)
target_compile_options(aether_loopmix PRIVATE -O3 -ffast-math -fvisibility=hidden -Wno-unused-parameter)
target_link_options(aether_loopmix PRIVATE "-Wl,-z,max-page-size=16384")
