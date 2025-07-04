# ScreenLinkNew - 屏幕接收器

这是一个Android屏幕接收应用，用于接收和显示其他设备的屏幕画面。

## 主要功能

### 🖥️ 屏幕接收功能
- **接收端模式**: 接收并显示其他设备的屏幕
- **WebRTC技术**: 使用WebRTC实现低延迟、高质量的屏幕接收
- **多设备支持**: 可以连接多个发送端设备
- **实时显示**: 实时接收和显示远程屏幕画面

### 🌐 网络通信功能
- **WebSocket信令**: 与信令服务器通信
- **自动连接**: 启动时自动连接到默认服务器
- **服务器配置**: 支持自定义服务器地址

## 技术架构

### 核心组件
- **MainDispatcherActivity**: 主界面，启动接收端
- **ReceiverMainActivity**: 接收端界面，显示远程屏幕

### 依赖库
- **WebRTC**: 用于实时音视频接收
- **Java-WebSocket**: WebSocket客户端
- **AndroidX**: 现代Android开发库

## 编译与安装

1. 打开命令行，进入 `screenlink_new` 目录：
   ```
   cd screenlink_new
   ```

2. 使用Gradle编译APK：
   ```
   ./gradlew assembleDebug
   ```
   或 Windows 下：
   ```
   gradlew.bat assembleDebug
   ```

3. 安装到设备（需已连接ADB）：
   ```
   ./gradlew installDebug
   ```

## 使用说明

### 接收端使用
1. 启动应用，点击"启动接收端"
2. 应用会自动连接到默认服务器 (192.168.1.2:6060)
3. 在发送端列表中选择要观看的设备
4. 开始接收屏幕画面

### 服务器配置
- **默认服务器**: 192.168.1.2:6060
- **自定义服务器**: 长按连接按钮显示服务器地址输入框
- **连接状态**: 界面底部显示当前连接状态

## 权限说明

应用需要以下权限：
- `INTERNET`: 网络通信
- `ACCESS_NETWORK_STATE`: 网络状态检查
- `RECORD_AUDIO`: 录音权限（WebRTC需要）
- `POST_NOTIFICATIONS`: 发送通知

## 注意事项

1. **Android版本**: 最低支持Android 7.0 (API 24)
2. **网络要求**: 需要稳定的网络连接
3. **信令服务器**: 需要运行配套的信令服务器
4. **发送端设备**: 需要发送端设备在线并共享屏幕

## 故障排除

### 常见问题
1. **无法连接服务器**: 检查网络连接和服务器地址
2. **没有发送端显示**: 检查发送端设备是否在线
3. **画面不显示**: 检查WebRTC连接状态

### 日志查看
使用 `adb logcat` 查看应用日志：
```bash
adb logcat | grep -E "(ScreenLink|MainDispatcher|ReceiverMain)"
```

## 开发说明

### 项目结构
```
screenlink_new/
├── app/
│   ├── src/main/
│   │   ├── java/com/screenlink/newapp/
│   │   │   ├── MainDispatcherActivity.kt    # 主界面
│   │   │   └── ReceiverMainActivity.kt      # 接收端界面
│   │   ├── res/
│   │   │   └── layout/
│   │   │       └── activity_receiver_main.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle
└── README.md
```

### 扩展开发
- 可以添加音频接收功能
- 可以增加更多视频解码选项
- 可以优化网络自适应算法
- 可以添加录制功能 