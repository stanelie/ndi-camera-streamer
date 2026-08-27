cmake_minimum_required(VERSION 3.22)
project(ndi_jni)

# Prebuilt NDI Advanced SDK shared library for this ABI, vendored from the NDI Advanced SDK
# v5 for Android (see ../../../../scripts/vendor_ndi_sdk.sh). Not built here — just linked.
# "Advanced" specifically because only that SDK tier exposes the compressed H.264/HEVC
# (NDI|HX) send path this app uses instead of standard NDI's software compressor. Pinned to
# v5.6.1 (named plain "libndi.so", unlike v6's "libndi_advanced.so") to match Millumin's
# bundled NDI 5.0.0 runtime — see vendor_ndi_sdk.sh for why.
add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so"
)

add_library(ndi_jni SHARED
    ndi_jni.cpp
)

target_include_directories(ndi_jni PRIVATE
    ${CMAKE_SOURCE_DIR}/ndi-include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(ndi_jni
    ndi
    ${log-lib}
    ${android-lib}
)
