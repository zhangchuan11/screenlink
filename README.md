# ScreenLink 手机同屏（投屏）项目

本项目实现了两个手机间的同屏（投屏）功能，支持Android和iOS平台。采用WebRTC技术实现高质量、低延迟的屏幕共享，并使用信令服务器进行连接管理。

## 项目架构

```
screenlink/
├── signaling_server/    # WebRTC信令服务器
├── android_sender/      # Android发送端
├── android_receiver/    # Android接收端
├── ios_sender/         # iOS发送端
├── ios_receiver/       # iOS接收端
└── README.md           # 项目说明文档
```

## 技术架构
### 信令服务器
- Node.js + WebSocket
- 负责设备发现和WebRTC信令交换
- 支持多房间管理和连接状态监控

### 客户端
- **发送端**：
  - 屏幕采集：Android MediaProjection / iOS ReplayKit
  - 视频编码：H.264 / WebRTC VideoEncoder
  - 数据传输：WebRTC DataChannel
  
- **接收端**：
  - 视频解码：WebRTC VideoDecoder
  - 画面渲染：Android SurfaceView / iOS AVSampleBufferDisplayLayer
  - 数据接收：WebRTC DataChannel

## 平台支持
- Android 8.0 及以上
- iOS 12 及以上
- 信令服务器支持：Windows、Linux、macOS

## 环境依赖
### 信令服务器
- Node.js 14.0+
- npm 或 yarn

### Android
- Android Studio Arctic Fox 或更高版本
- Gradle 7.0+
- WebRTC Android SDK

### iOS
- Xcode 13.0+
- CocoaPods
- WebRTC iOS SDK

## 快速开始

### 1. 启动信令服务器
```bash
# 进入信令服务器目录
cd signaling_server

# 安装依赖
npm install

# 启动服务器
# Windows
start.bat
# Linux/macOS
./start.sh
```

### 2. 运行客户端
#### 发送端
1. 启动发送端App
2. 输入房间号（用于标识连接）
3. 授权屏幕录制权限
4. 点击"开始共享"

#### 接收端
1. 启动接收端App
2. 输入相同的房间号
3. 等待连接建立，开始接收画面

## 网络要求
- 信令服务器需要有公网IP或域名
- 客户端需要能访问信令服务器
- P2P连接失败时会自动转为中继模式

## 目录说明
- `signaling_server/`：WebRTC信令服务器
  - `server.js`：基础信令服务器实现
  - `server_advanced.js`：高级功能服务器实现
  - `start.bat/start.sh`：启动脚本
- `android_sender/`：Android发送端源码
- `android_receiver/`：Android接收端源码
- `ios_sender/`：iOS发送端源码
- `ios_receiver/`：iOS接收端源码

## 性能优化
- 支持动态码率调整
- 自适应网络带宽
- 支持硬件编解码
- 多线程优化

## 注意事项
- 首次运行需要授予相关权限
- 建议在5GHz Wi-Fi或较好的网络环境下使用
- 屏幕共享会消耗较多电量和数据流量

## 问题排查
1. 连接问题
   - 检查信令服务器是否正常运行
   - 确认网络连接状态
   - 查看客户端日志

2. 画面问题
   - 检查编解码器兼容性
   - 确认设备性能是否足够
   - 调整码率和分辨率设置

---

如需技术支持或发现Bug，请提交Issue或联系开发团队。 