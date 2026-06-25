# MoleculeDrawer

MoleculeDrawer 是一款 Android 分子结构绘制应用，用于在移动端创建、编辑、导入、导出和预览二维化学结构。项目可作为独立应用使用，也可作为 ChemELN 等化学实验记录软件中的结构编辑器组件。

## 主要功能

- 原子与化学键绘制：支持常见元素、单键、双键、三键、芳香键和立体键。
- 结构编辑：支持选择、移动、删除、撤销、模板化绘制等编辑操作。
- 标注能力：支持文本、箭头、功能团等结构注释。
- JSON 工作区格式：使用 `moldraw` JSON 保存画布、原子、化学键和标注数据。
- 导入导出：支持结构数据在独立应用和外部应用之间传递。
- Indigo 支持：包含 Indigo 相关模块，用于扩展化学结构处理能力。

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
