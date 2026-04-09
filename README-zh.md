# Android-Usbipdcpp

[English](README.md)

📱🔌 一个 Android USB/IP 服务端应用，通过网络共享 USB 设备。

## ✨ 功能

- 🔗 从 Android 共享 USB 设备到远程计算机
- 📦 支持多设备
- ⚙️ 可配置端口
- 🌐 中英文界面
- 📋 实时日志

## 📋 要求

| 项目 | 要求 |
|------|------|
| 🤖 Android | 8.1+ (API 28+) |
| 💻 硬件 | USB OTG 支持 |
| 🖥️ 客户端 | USB/IP 客户端（如 Linux 的 `usbip`） |

## 🚀 使用

```
Android 设备                      远程计算机
    │                                │
    │  1. 🔌 连接 USB 设备           │
    │  2. ▶️ 启动服务器              │
    │  3. ✅ 授予 USB 权限           │
    │  4. 📎 绑定设备                │
    │                                │
    │◀──────── 网络 ────────────────▶│
    │                                │
    │                    5. 📋 usbip list -r <ip>
    │                    6. 🔗 usbip attach -r <ip> -b <busid>
```

## 🔨 编译

```bash
# 克隆
git clone https://github.com/yunsmall/Android-Usbipdcpp.git

# 使用 Android Studio 编译（需要 NDK）
```

## 📄 许可证

[GPL-3.0](LICENSE)
