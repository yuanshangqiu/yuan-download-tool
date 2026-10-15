# 网络加速集成

YunX 在「网络加速」页集成 HypoMux 2.7.0 Core 的普通代理模式。选中多张活动网卡后，Core 会为各个新连接选择出口网卡；YunX 的并发分片下载因此可以同时使用多条链路。自适应吞吐调度会依据引擎采样调整后续连接的分配。

## 范围

- 加速流量限于 YunX 自身的 HTTP API 与下载请求。
- 不接管 Windows 系统代理、默认路由、DNS 或其他应用流量。
- 至少选择两张具有 IPv4 地址的活动网卡；虚拟网卡默认不选。
- 通过普通本地 HTTP 代理运行，不需要管理员权限。
- 该集成未启用 HypoMux 的虚拟网卡 / TUN 模式，也不包含 sing-box 或 Wintun 运行时。
- 用户在 YunX 设置里配置的系统 / 手动 HTTP 代理会在聚合运行期间临时让位；停止聚合后立即恢复。

## 上游来源与许可

`desktop/hypomux-engine/` 保留自 HypoMux 2.7.0 的 Go 引擎源码、模块校验文件与许可证。构建时生成 Windows x64 `hypomux-engine.exe` 并打入 YunX 应用资源。HypoMux 引擎依照该目录内的 GNU AGPL-3.0 许可证分发；其来源为 [Hypostasis-Cat/HypoMux](https://github.com/Hypostasis-Cat/HypoMux)。

## 构建要求

从源码构建需要 Windows x64、JDK 17、Gradle 和 Go 1.26.6 或更新版本。Go 会按 `go.mod` 自动选择所需工具链；首次构建需要访问 Go 模块源。完整步骤见 [构建与打包](BUILD.md)。
