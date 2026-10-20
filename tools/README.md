# 本机开发工具

本目录保留本机离线开发与验证需要的工具，不包含项目源码。

| 路径 | 用途 |
|---|---|
| `java/jdk-21.0.12.1+1` | 项目使用的 JDK 21 |
| `maven/repository` | 已下载的依赖和构建插件缓存 |
| `maven/settings.xml` | 独立测试使用的最小 settings |

在项目根目录执行 `./scripts/Test-Project.ps1 -Offline` 进行测试和构建。Maven 与 MySQL 使用本机已安装的程序，通过 PATH 定位。

原 downloads/jdk21.zip 已在核对解压后的 486 个文件摘要一致后删除；JDK 安装目录和 Maven 缓存继续保留。清理规则见 [项目目录指南](../docs/Project_Directory_Guide.md)。此前迁移摘要当前缺失，不作为本轮完整性依据。

JDK 和缓存不提交 Git，更换机器时需另行准备；无需将下载压缩包长期保留在开发工作区。
