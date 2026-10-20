# 实验模型配置

默认运行器只读取本目录的 model.local.properties；模板见 [model.example.properties](./model.example.properties)。原根目录 config_al/model.local.properties 已原样移动到这里，不保留第二份配置来源。

本机文件被 Git 忽略，配置里的接口、模型名、参数及密钥环境变量名未改动。后端应用配置仍在 backend/src/main/resources，不与本实验配置混用。更换机器时复制模板并本地配置；不要提交密钥。

显式传入 Run-S1Ai.ps1 的 -Config 参数和原有环境变量机制保持原逻辑。返回 [实验说明](../README.md)。
