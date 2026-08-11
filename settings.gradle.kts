// 镜像仓库：默认启用（适合国内网络）；CI/海外环境设置 USE_MIRRORS=false 禁用
// 注意：pluginManagement 块内有独立作用域，不能引用外部变量，需直接读环境变量

pluginManagement {
    repositories {
        if (System.getenv("USE_MIRRORS") != "false") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://repo.huaweicloud.com/repository/gradle-plugin/")
            maven("https://repo.huaweicloud.com/repository/maven/")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("USE_MIRRORS") != "false") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://maven.aliyun.com/repository/public")
            maven("https://repo.huaweicloud.com/repository/maven/")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "moleculedrawer"
include(":moleculedrawer")
include(":moleculedrawer-indigo")
include(":moleculedrawer-indigo-native")
