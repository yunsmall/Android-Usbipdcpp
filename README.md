# Android-Usbipdcpp

[中文](README-zh.md)

📱🔌 An Android USB/IP server application for sharing USB devices over the network.

## ✨ Features

- 🔗 Share USB devices from Android to remote computers
- 📦 Multiple device support
- ⚙️ Configurable server port
- 🌐 Chinese/English UI
- 📋 Real-time log viewer

## 📋 Requirements

| Item | Requirement |
|------|-------------|
| 🤖 Android | 8.1+ (API 28+) |
| 💻 Hardware | USB OTG support |
| 🖥️ Client | USB/IP client (e.g., `usbip` on Linux) |

## 🚀 Usage

```
Android Device                    Remote Computer
    │                                   │
    │  1. 🔌 Connect USB device         │
    │  2. ▶️ Start server               │
    │  3. ✅ Grant USB permission        │
    │  4. 📎 Bind device                │
    │                                   │
    │◀──────── Network ────────────────▶│
    │                                   │
    │                    5. 📋 usbip list -r <ip>
    │                    6. 🔗 usbip attach -r <ip> -b <busid>
```

## 🔨 Build

```bash
# Clone
git clone https://github.com/yunsmall/Android-Usbipdcpp.git

# Build with Android Studio (NDK required)
```

## 📄 License

[GPL-3.0](LICENSE)
