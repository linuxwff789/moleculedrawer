# MoleculeDrawer

MoleculeDrawer 是一款 Android 分子结构绘制应用，用于在移动端创建、编辑、导入、导出和预览二维化学结构。项目可作为独立应用使用，也可作为 ChemELN 等化学实验记录软件中的结构编辑器组件。

## 主要功能

- 原子与化学键绘制：支持常见元素、单键、双键、三键、芳香键和立体键。
- 结构编辑：支持选择、移动、删除、撤销/重做、模板化绘制、双指缩放等编辑操作。
- 绘制样式：经典 / 专业两套样式（专业样式对齐 ChemDraw：碳不画点、双键长短线、氢下标、标签白底）。
- 官能团缩写：26 个常用基团按「单原子缩写」模型存储（同 ChemDraw abbreviation / MDL SUP sgroup），
  末端碳替换、环上碳挂接，自动调整后标记不丢。
- 标注能力：文本、箭头、功能团注释，支持自动下标。
- **查结构**：内置 ChemicalBook 页面搜索中文名/英文名/CAS，从理化信息页提取结构导入画布。
- **结构反查**：用画布生成的 SMILES 精确匹配 PubChem，得到名称 / CAS / 分子式 / 分子量；
  也可把结构直接灌进 ChemicalBook 的结构式搜索（Ketcher）里。
- JSON 工作区格式：使用 `moldraw` JSON 保存画布、原子、化学键和标注数据。
- 导入导出：SMILES / Molfile（V2000、标准坐标尺度）/ PNG / SVG / PDF / moldraw JSON。
- Indigo 支持：包含 Indigo 相关模块，用于布局、SMILES 生成与分子性质计算。

## 查结构 / 反查的取数规则

ChemicalBook 没有公开 API，中文站搜索是表单 POST，详情页对非浏览器请求返回 503，
所以 App 用 WebView 走用户自己的浏览会话，只读取页面上已经公开展示的结构信息。结构来源按优先级：

1. 页面上的 `.mol` / `.sdf` 链接（`/CAS/mol/{CAS}.mol`、`/CAS/{yyyyMMdd}/mol/{CAS}.mol` 等形态）
2. 页面上标注的 SMILES
3. 页面上的 InChIKey → PubChem 换取 SMILES
4. 页面上的 CAS 号 → PubChem 换取 SMILES

反方向（结构 → 名称）只能用 PubChem：`/rest/pug/compound/smiles/{smiles}/cids/JSON` 精确结构匹配，
CAS 从 `xrefs/RegistryID` 里取。

## 自测

改完代码可以先跑无界面自测，不用一遍遍装机点屏幕：

```bash
adb shell am force-stop com.moldraw.app
adb shell am start -n com.moldraw.app/.ui.moleculedraw.MoleculeDrawActivity --es moldraw_selftest 1
adb shell run-as com.moldraw.app cat files/selftest.txt   # 或 logcat -s SELFTEST
```

覆盖：26 个官能团的展开/替换/放置语义、双键几何（环内/稠环/末端/C=O 等长）、
V2000 定宽格式与坐标尺度、PubChem 反查。

## 项目结构

```text
.
├── moleculedrawer/                # Android 分子编辑器应用主模块
├── moleculedrawer-indigo/         # Indigo Kotlin/Android 封装模块
├── moleculedrawer-indigo-native/  # Indigo native 库模块
├── gradle/                        # Gradle Wrapper 配置
├── build.gradle.kts               # 根 Gradle 配置
└── settings.gradle.kts            # 工程模块配置
```

## 构建方式

环境要求：

- JDK 17
- Android SDK
- Gradle Wrapper（仓库已内置）

构建 Debug APK：

```bash
./gradlew :moleculedrawer:assembleDebug
```

构建产物位置：

```text
moleculedrawer/build/outputs/apk/debug/moleculedrawer-debug.apk
```

如果仓库中包含 `release/moleculedrawer-debug.apk`，该文件是已构建好的 Debug 安装包，便于快速测试。

## moldraw JSON 简介

MoleculeDrawer 使用 `moldraw` JSON 表示分子结构画布，核心字段包括：

- `format` / `version`：数据格式标识与版本。
- `atoms`：原子列表，包含坐标、元素、芳香性、手性和功能团信息。
- `bonds`：化学键列表，包含连接原子和键类型。
- `annotations`：文本、箭头、功能团等画布标注。
- 渲染参数：键长、线宽、字体大小、芳香环样式等。

## 与其他仓库的关系

MoleculeDrawer 已从原始 monorepo 中拆分为独立仓库。相关项目：

- ChemELN：化学电子实验记录本。
- MoleculeRenderLib：分子结构渲染库。
- moleculedrawer-monorepo：历史总仓库备份。

## 版权

Copyright (c) 2026 linuxwff789 and MoleculeDrawer contributors.

本项目代码和资源的使用、复制、修改与分发须遵循本仓库 `LICENSE` 文件。第三方依赖，包括 Indigo 相关组件，仍遵循其各自许可证。
