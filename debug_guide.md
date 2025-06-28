# WebRTC应用调试指南

## 当前问题分析

从日志分析，应用已经成功启动并连接到信令服务器，但存在以下问题：

1. **没有发送端注册** - 发送端列表为空 `{"type":"sender_list","senders":[]}`
2. **只有接收端在运行** - 当前设备以接收端模式运行
3. **客户端选择正常** - 可以选择客户端ID 5，但没有视频流

## 解决方案

### 步骤1: 设置发送端设备

**在另一个设备上（或重新启动当前设备）：**

1. **启动应用**
2. **点击"切换到发送端模式"按钮**
3. **授予屏幕录制权限** - 这是关键步骤！
4. **检查通知栏** - 应该显示"ScreenLink 屏幕共享"通知
5. **查看日志** - 应该看到以下日志：
   ```
   MainActivity: 已切换到发送端模式
   ScreenCaptureManager: 开始屏幕捕获
   ScreenCaptureService: 前台服务已启动
   WebRTCManager: 注册消息已发送
   ```

### 步骤2: 验证发送端状态

**发送端设置成功后，你应该看到：**

- ✅ 通知栏显示"ScreenLink 屏幕共享"通知
- ✅ 应用状态显示"发送端模式 - 屏幕捕获已开始"
- ✅ 服务器日志显示发送端注册：
  ```
  收到原始消息: {"type":"register_sender","name":"发送端"}
  ```

### 步骤3: 设置接收端设备

**在接收端设备上：**

1. **启动应用**（默认是接收端模式）
2. **等待连接** - 应该自动连接到服务器
3. **查看客户端列表** - 应该显示发送端
4. **选择发送端** - 点击发送端名称
5. **点击连接按钮** - 开始建立WebRTC连接

### 步骤4: 验证连接

**连接成功后，你应该看到：**

- ✅ 接收端显示发送端的屏幕内容
- ✅ 日志显示WebRTC连接建立
- ✅ 视频流正常传输

## 常见问题排查

### 问题1: 发送端没有注册到服务器

**可能原因：**
- 屏幕录制权限被拒绝
- 前台服务没有正确启动
- MediaProjection创建失败

**解决方法：**
1. 检查权限设置
2. 重启应用并重新授予权限
3. 查看日志确认前台服务启动

### 问题2: 接收端看不到发送端

**可能原因：**
- 发送端没有正确注册
- 网络连接问题
- 服务器配置问题

**解决方法：**
1. 确认发送端已注册
2. 检查网络连接
3. 重启发送端和接收端

### 问题3: 连接后没有视频流

**可能原因：**
- WebRTC连接建立失败
- ICE候选者交换失败
- 视频轨道没有正确添加

**解决方法：**
1. 检查WebRTC连接状态
2. 查看ICE候选者交换日志
3. 确认PeerConnection状态

## 调试命令

### 查看应用日志
```bash
adb logcat | grep -E "(MainActivity|WebRTCManager|ScreenCapture|BackgroundService)"
```

### 查看前台服务状态
```bash
adb shell dumpsys activity services | grep ScreenCapture
```

### 检查权限状态
```bash
adb shell dumpsys package com.screenlink.newapp | grep -A 10 "requested permissions"
```

## 预期日志流程

### 发送端启动流程
```
MainActivity: 已切换到发送端模式
ScreenCaptureManager: 开始屏幕捕获
ScreenCaptureService: 前台服务已启动
ScreenCaptureService: 屏幕捕获已开始
WebRTCManager: 注册消息已发送
```

### 接收端连接流程
```
WebRTCManager: 收到发送端列表，数量: 1
MainActivity: 已选择客户端: 发送端
WebRTCManager: 开始创建PeerConnection
PeerConnectionManager: Offer创建成功
WebRTCManager: Offer已发送
```

## 测试步骤总结

1. **设备A（发送端）**：
   - 启动应用 → 切换到发送端模式 → 授予权限 → 等待注册

2. **设备B（接收端）**：
   - 启动应用 → 等待连接 → 选择发送端 → 点击连接 → 查看视频

3. **验证结果**：
   - 发送端显示前台服务通知
   - 接收端显示发送端屏幕内容
   - 日志显示连接建立成功

## 联系支持

如果按照以上步骤仍然无法解决问题，请提供：
1. 完整的应用日志
2. 服务器日志
3. 设备型号和Android版本
4. 具体的错误信息 