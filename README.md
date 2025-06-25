# 手机同屏（投屏）项目

本项目实现了两个手机间的同屏（投屏）功能，支持Android和iOS平台，采用最简单的Socket直连方式进行屏幕采集、编码、推流与播放。

## 目录结构

```
touping/
├── android_sender/      # Android发送端
├── android_receiver/    # Android接收端
├── ios_sender/          # iOS发送端
├── ios_receiver/        # iOS接收端
└── README.md            # 总体说明和使用方法
```

## 功能说明
- **发送端**：采集本机屏幕，编码为H.264，通过Socket推送到接收端
- **接收端**：通过Socket接收H.264流，解码并实时播放

## 平台支持
- Android 8.0 及以上
- iOS 12 及以上

## 依赖说明
### Android
- Android Studio
- 主要用到：MediaProjection、MediaCodec、Socket

### iOS
- Xcode
- 主要用到：ReplayKit、VideoToolbox、Socket

## 使用方法
### 1. 发送端
- 启动发送端App，授权录屏权限，输入接收端IP和端口，点击开始投屏

### 2. 接收端
- 启动接收端App，监听指定端口，等待接收并播放

### 3. 网络要求
- 两台设备需在同一Wi-Fi局域网下

## 目录说明
- `android_sender/`：Android发送端源码
- `android_receiver/`：Android接收端源码
- `ios_sender/`：iOS发送端源码
- `ios_receiver/`：iOS接收端源码

## 注意事项
- 本项目为最简Demo，未做复杂异常处理和安全加固，仅供学习和原型参考。
- 屏幕采集和推流会消耗较多CPU和带宽，建议在性能较好的设备上测试。

---

如需详细开发文档或遇到问题，请联系开发者。 