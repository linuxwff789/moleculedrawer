cmake_minimum_required(VERSION 3.22.1)
project(indigo_jni_bridge LANGUAGES C CXX)

# 预编译的 Indigo 库路径
set(INDIGO_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# JNI 桥接源码
file(GLOB JNI_SOURCES jni/*.cpp jni/*.h)

add_library(indigo-jni SHARED ${JNI_SOURCES})

target_include_directories(indigo-jni
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/include
)

# 链接预编译的 Indigo 库
target_link_libraries(indigo-jni
    PRIVATE ${INDIGO_LIBS_DIR}/libindigo.so
    PRIVATE ${INDIGO_LIBS_DIR}/libindigo-inchi.so
    PRIVATE log
)

# 拷贝 Indigo .so 到输出目录
add_custom_command(TARGET indigo-jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    ${INDIGO_LIBS_DIR}/libindigo.so
    ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/
)
add_custom_command(TARGET indigo-jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    ${INDIGO_LIBS_DIR}/libindigo-inchi.so
    ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/
)