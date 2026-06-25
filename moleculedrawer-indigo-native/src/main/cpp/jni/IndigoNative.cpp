#include <jni.h>
#include <string>
#include <cstring>
#include <cstdio>
#include <android/log.h>

// Indigo C API
extern "C" {
#include "indigo.h"
}

// 辅助宏：INDIGO_JNI 日志标签
#define LOG_TAG "INDIGO_JNI"

// 辅助函数：JString → char*
static std::string jstring2string(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

// ============================================================
// JNI: IndigoNative.loadMolecule(String molfile) → int handle
// ============================================================
extern "C" JNIEXPORT jint JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_loadMolecule(
    JNIEnv* env, jclass clazz, jstring molfile) {
    std::string mol = jstring2string(env, molfile);
    int handle = indigoLoadMoleculeFromString(mol.c_str());
    return static_cast<jint>(handle);
}

// ============================================================
// JNI: IndigoNative.layout(int handle) → boolean
// ============================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_layout(
    JNIEnv* env, jclass clazz, jint handle) {
    try {
        indigoLayout(static_cast<int>(handle));
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

// ============================================================
// JNI: IndigoNative.clean2d(int handle) → boolean
// ============================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_clean2d(
    JNIEnv* env, jclass clazz, jint handle) {
    try {
        indigoClean2d(static_cast<int>(handle));
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}

// ============================================================
// JNI: IndigoNative.layoutOnly(String molfile) → String
// 一站式：加载 → 布局（不含美化 clean2d）→ 输出 molfile
// 不调用 clean2d，避免 clean2d 对长链烷烃过度优化导致误识别为环。
// ============================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_layoutOnly(
    JNIEnv* env, jclass clazz, jstring molfile) {
    try {
        // Indigo 使用 session（状态机），必须先分配 session ID
        qword sid = indigoAllocSessionId();
        indigoSetSessionId(sid);
        
        std::string mol = jstring2string(env, molfile);
        
        // 设置输出格式为 V3000
        indigoSetOption("molfile-saving-mode", "3000");
        // 保留立体化学信息（如楔形键）
        indigoSetOption("ignore-stereocenter-errors", "true");
        
        // 加载分子
        int molHandle = indigoLoadMoleculeFromString(mol.c_str());
        if (molHandle < 0) {
            const char* err = indigoGetLastError();
            __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "layoutOnly: indigoLoadMoleculeFromString failed: %s", err ? err : "unknown");
            return env->NewStringUTF("");
        }
        
        // 仅布局，不调用 clean2d
        indigoLayout(molHandle);
        
        // 输出 molfile
        const char* result = indigoMolfile(molHandle);
        if (result == nullptr) {
            const char* err = indigoGetLastError();
            __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "layoutOnly: indigoMolfile returned nullptr: %s", err ? err : "unknown");
            indigoFree(molHandle);
            return env->NewStringUTF("");
        }
        
        jstring jresult = env->NewStringUTF(result);
        indigoFree(molHandle);
        return jresult;
    } catch (std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "layoutOnly: std::exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "layoutOnly: unknown exception");
        return env->NewStringUTF("");
    }
}

// ============================================================
// JNI: IndigoNative.layoutAndClean(String molfile) → String
// 一站式：加载 → 布局 → 美化 → 输出 molfile
// 注意：clean2d 对长链烷烃可能会过度优化，推荐使用 layoutOnly
// ============================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_layoutAndClean(
    JNIEnv* env, jclass clazz, jstring molfile) {
    try {
        // Indigo 使用 session（状态机），必须先分配 session ID
        qword sid = indigoAllocSessionId();
        indigoSetSessionId(sid);
        
        std::string mol = jstring2string(env, molfile);
        
        // 设置输出格式为 V3000
        indigoSetOption("molfile-saving-mode", "3000");
        
        // 加载分子
        int molHandle = indigoLoadMoleculeFromString(mol.c_str());
        if (molHandle < 0) {
            const char* err = indigoGetLastError();
            __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "layoutAndClean: indigoLoadMoleculeFromString failed: %s", err ? err : "unknown");
            return env->NewStringUTF("");
        }
        
        // 布局
        indigoLayout(molHandle);
        
        // 美化
        indigoClean2d(molHandle);
        
        // 输出 molfile
        const char* result = indigoMolfile(molHandle);
        if (result == nullptr) {
            indigoFree(molHandle);
            return env->NewStringUTF("");
        }
        
        jstring jresult = env->NewStringUTF(result);
        indigoFree(molHandle);
        return jresult;
    } catch (...) {
        return env->NewStringUTF("");
    }
}

// ============================================================
// JNI: IndigoNative.toSmiles(String molfile) → String
// 加载分子 → 输出 canonical SMILES
// ============================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_toSmiles(
    JNIEnv* env, jclass clazz, jstring molStr) {
    try {
        qword sid = indigoAllocSessionId();
        indigoSetSessionId(sid);
        
        std::string mol = jstring2string(env, molStr);
        __android_log_print(ANDROID_LOG_DEBUG, "INDIGO_JNI", "toSmiles: input (%d chars): %.200s", (int)mol.size(), mol.c_str());
        
        int molHandle = indigoLoadMoleculeFromString(mol.c_str());
        if (molHandle < 0) {
            const char* err = indigoGetLastError();
            __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "toSmiles: indigoLoadMoleculeFromString failed: %s", err ? err : "unknown");
            return env->NewStringUTF("");
        }
        __android_log_print(ANDROID_LOG_DEBUG, "INDIGO_JNI", "toSmiles: loaded handle=%d", molHandle);
        
        const char* result = indigoSmiles(molHandle);
        if (result == nullptr) {
            const char* err = indigoGetLastError();
            __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "toSmiles: indigoSmiles returned nullptr: %s", err ? err : "unknown");
            indigoFree(molHandle);
            return env->NewStringUTF("");
        }
        __android_log_print(ANDROID_LOG_DEBUG, "INDIGO_JNI", "toSmiles: result='%s'", result);
        
        jstring jresult = env->NewStringUTF(result);
        indigoFree(molHandle);
        return jresult;
    } catch (std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "toSmiles: std::exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "toSmiles: unknown exception");
        return env->NewStringUTF("");
    }
}

// ============================================================
// JNI: IndigoNative.toSmilesFromAtoms(float[] coords, String[] elements, int[] bonds) → String
// 直接用原子和键信息生成 SMILES，不经过 V3000
// ============================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_toSmilesFromAtoms(
    JNIEnv* env, jclass clazz, jfloatArray xCoords, jfloatArray yCoords,
    jobjectArray elements, jintArray bondPairs, jintArray bondTypes) {

    try {
        qword sid = indigoAllocSessionId();
        indigoSetSessionId(sid);

        int atomCount = env->GetArrayLength(xCoords);
        if (atomCount == 0) return env->NewStringUTF("");

        jfloat* xs = env->GetFloatArrayElements(xCoords, nullptr);
        jfloat* ys = env->GetFloatArrayElements(yCoords, nullptr);

        // 创建 Indigo 分子
        int mol = indigoCreateMolecule();

        // 添加原子
        int* atomHandles = new int[atomCount];
        for (int i = 0; i < atomCount; i++) {
            jstring elemStr = (jstring)env->GetObjectArrayElement(elements, i);
            const char* elem = env->GetStringUTFChars(elemStr, nullptr);
            atomHandles[i] = indigoAddAtom(mol, elem);
            // 设置坐标 (Indigo坐标单位是Å，像素需要转换)
            // 这里直接设坐标，但indigoSmiles()不需要坐标，只输出拓扑
            env->ReleaseStringUTFChars(elemStr, elem);
            env->DeleteLocalRef(elemStr);
        }

        // 添加键
        jint* pairs = env->GetIntArrayElements(bondPairs, nullptr);
        jint* types = nullptr;
        if (bondTypes != nullptr) {
            types = env->GetIntArrayElements(bondTypes, nullptr);
        }
        int bondCount = env->GetArrayLength(bondPairs) / 2;
        for (int i = 0; i < bondCount; i++) {
            int a1 = pairs[i * 2] - 1; // 转为0-based
            int a2 = pairs[i * 2 + 1] - 1;
            int bt = (types != nullptr) ? types[i] : 1;
            if (a1 >= 0 && a1 < atomCount && a2 >= 0 && a2 < atomCount) {
                // indigoAddBond 接受 source, destination, order
                indigoAddBond(atomHandles[a1], atomHandles[a2], bt);
            }
        }

        // 设置 SMILES 输出模式为 stereo，保留手性/楔形键信息
        indigoSetOption("smiles-saving-mode", "stereo");
        // 禁用 canonical 排序，保持原子添加顺序
        indigoSetOption("smiles-output-order", "preserve");
        
        // 输出 SMILES
        const char* result = indigoSmiles(mol);
        jstring jresult = env->NewStringUTF(result ? result : "");

        // 清理
        env->ReleaseFloatArrayElements(xCoords, xs, 0);
        env->ReleaseFloatArrayElements(yCoords, ys, 0);
        env->ReleaseIntArrayElements(bondPairs, pairs, 0);
        if (types != nullptr) env->ReleaseIntArrayElements(bondTypes, types, 0);
        delete[] atomHandles;
        indigoFree(mol);

        return jresult;
    } catch (std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "toSmilesFromAtoms: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        return env->NewStringUTF("");
    }
}
// ============================================================
// JNI: IndigoNative.grossFormula(String molfile) → String
// 使用 Indigo 计算分子式（含隐式氢）
// ============================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_grossFormula(
    JNIEnv* env, jclass clazz, jstring molInput) {
    try {
        qword sid = indigoAllocSessionId();
        indigoSetSessionId(sid);
        
        std::string input = jstring2string(env, molInput);
        
        // 加载分子（支持 SMILES 或 molfile）
        int molHandle = indigoLoadMoleculeFromString(input.c_str());
        if (molHandle < 0) {
            const char* err = indigoGetLastError();
            __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "grossFormula: load failed: %s", err ? err : "unknown");
            return env->NewStringUTF("");
        }
        
        // 调用 Indigo 的 grossFormula API（返回 handle，需要 indigoToString）
        int formulaHandle = indigoGrossFormula(molHandle);
        if (formulaHandle < 0) {
            __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "grossFormula: returned negative handle");
            indigoFree(molHandle);
            return env->NewStringUTF("");
        }
        const char* formula = indigoToString(formulaHandle);
        
        jstring jresult = env->NewStringUTF(formula);
        __android_log_print(ANDROID_LOG_DEBUG, "INDIGO_JNI", "grossFormula: '%s'", formula);
        indigoFree(molHandle);
        return jresult;
    } catch (std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "INDIGO_JNI", "grossFormula: exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        return env->NewStringUTF("");
    }
}

// ============================================================
// JNI: IndigoNative.molecularWeight(String molInput) → double
// 使用 Indigo 计算分子量（含隐式氢）
// ============================================================
extern "C" JNIEXPORT jdouble JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_molecularWeight(
    JNIEnv* env, jclass clazz, jstring molInput) {
    try {
        qword sid = indigoAllocSessionId();
        indigoSetSessionId(sid);
        
        std::string input = jstring2string(env, molInput);
        
        int molHandle = indigoLoadMoleculeFromString(input.c_str());
        if (molHandle < 0) {
            return -1.0;
        }
        
        float weight = indigoMolecularWeight(molHandle);
        indigoFree(molHandle);
        return static_cast<jdouble>(weight);
    } catch (...) {
        return -1.0;
    }
}

// ============================================================
// JNI: IndigoNative.free(int handle)
// ============================================================
extern "C" JNIEXPORT void JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_free(
    JNIEnv* env, jclass clazz, jint handle) {
    indigoFree(static_cast<int>(handle));
}

// ============================================================
// JNI: IndigoNative.getVersion() → String
// ============================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_getVersion(
    JNIEnv* env, jclass clazz) {
    const char* ver = indigoVersion();
    if (ver == nullptr) ver = "unknown";
    return env->NewStringUTF(ver);
}

// ============================================================
// JNI: IndigoNative.setOption(String name, String value)
// ============================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_moldraw_app_indigo_1native_IndigoNative_setOption(
    JNIEnv* env, jclass clazz, jstring name, jstring value) {
    std::string n = jstring2string(env, name);
    std::string v = jstring2string(env, value);
    try {
        indigoSetOption(n.c_str(), v.c_str());
        return JNI_TRUE;
    } catch (...) {
        return JNI_FALSE;
    }
}