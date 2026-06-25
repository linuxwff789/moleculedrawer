# MoleculeDrawer 编译环境记录

## 项目位置
- 根目录：`/data/user/0/com.ai.assistance.operit/files/workspace/7276c886-db5c-49c1-883c-2a213848f352/`
- 主 module：`moleculedrawer/`
- Indigo JNI bridge module：`moleculedrawer-indigo-native/`

## 编译命令
```bash
cd /data/user/0/com.ai.assistance.operit/files/workspace/7276c886-db5c-49c1-883c-2a213848f352
./gradlew :moleculedrawer:assembleDebug
```

安装命令：
```bash
cp /path/to/apk /data/local/tmp/
pm install -r /data/local/tmp/apk_name.apk
```

## Indigo JNI so 编译

### 工具链：Termux aarch64-linux-android-clang
Termux 中安装了 aarch64 交叉编译器，可直接在设备上编译 Android NDK so。

编译命令（不依赖 libindigo-inchi）：
```bash
export PATH=/data/data/com.termux/files/usr/bin:$PATH
cd /data/data/com.termux/files/home/jni_build
aarch64-linux-android-clang++ -shared -o jniLibs/arm64-v8a/libindigo-jni.so \
  jni/IndigoNative.cpp \
  -I. -I/data/data/com.termux/files/usr/include \
  -L jniLibs/arm64-v8a -lindigo -llog \
  -fPIC -std=c++17 -O2
```

### 编译步骤
1. 复制源码到 Termux：
   ```
   IndigoNative.cpp → /data/data/com.termux/files/home/jni_build/jni/
   indigo.h → /data/data/com.termux/files/home/jni_build/
   libindigo.so → jniLibs/arm64-v8a/
   ```
2. 执行编译命令
3. 复制产物回项目：
   ```
   cp jniLibs/arm64-v8a/libindigo-jni.so \
     /path/to/moleculedrawer-indigo-native/src/main/jniLibs/arm64-v8a/
   ```

### 重要：不链接 libindigo-inchi
`libindigo-inchi.so` 被误替换为 Linux glibc 版本（依赖 `libm.so.6`），Android 无法加载。
编译时去掉 `-lindigo-inchi` 参数，JNI 桥接不依赖 InChI 功能。

### 已暴露的 JNI 函数
- `loadMolecule(String)` → int handle
- `layout(int handle)` → boolean
- `clean2d(int handle)` → boolean
- `layoutOnly(String molfile)` → String (V3000)
- `layoutAndClean(String molfile)` → String (V3000)
- `toSmiles(String molStr)` → String (canonical SMILES)
- `toSmilesFromAtoms(FloatArray, FloatArray, Array<String>, IntArray, IntArray?)` → String
- `grossFormula(String smiles)` → String (如 "C6H6")
- `molecularWeight(String smiles)` → Double
- `free(int handle)`
- `getVersion()` → String
- `setOption(String name, String value)` → boolean

### 加载顺序（IndigoNative.kt）
先加载依赖库，再加载 JNI 桥接：
```kotlin
System.loadLibrary("indigo")        // 核心库，Android NDK 版
System.loadLibrary("indigo-jni")    // JNI 桥接
```
`libindigo.so` 依赖 Android 系统库：`libc++_shared.so`, `libm.so`, `libdl.so`, `libc.so`

### 已知问题
- `libindigo-inchi.so` 被 Linux glibc 版本覆盖，Android 无法加载
- 解决方案：不加载 `libindigo-inchi.so`，InChI 功能暂不可用
- 需要重新获取 Android NDK 版本的 `libindigo-inchi.so` 才能恢复
- NDK 的 llvm-strip 是 x86_64 二进制，无法在当前 aarch64 环境执行
- 已在 build.gradle.kts 中通过 `whenTaskAdded` 自动将 merged so 复制到 stripped 目录
