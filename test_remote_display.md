# 远程投屏功能测试说明

## 功能概述
实现了简单的远程投屏功能，包括：
1. 发送端：Android设备作为屏幕共享源
2. 接收端：Android设备作为屏幕显示端
3. 信令服务器：协调发送端和接收端的连接

## 测试步骤

### 1. 启动信令服务器
```bash
cd signaling_server
node server.js
```

### 2. 配置网络
确保所有设备都在同一个局域网内，并修改以下文件中的IP地址：
- `SignalingClient.kt` 中的 `SERVER_URL` 改为你的服务器IP

### 3. 测试发送端
1. 在Android设备上安装应用
2. 启动应用，点击"开始屏幕共享"
3. 授予屏幕录制权限
4. 应用会自动连接到信令服务器并注册为发送端

### 4. 测试接收端
1. 在另一个Android设备上安装应用
2. 启动应用，点击"远程投屏接收"
3. 应用会自动连接到信令服务器并获取发送端列表
4. 点击"连接"按钮开始接收远程屏幕

## 预期结果
- 发送端：显示"屏幕共享已启动"
- 接收端：显示远程设备的屏幕内容
- 信令服务器：显示连接日志

## 故障排除
1. 如果连接失败，检查网络连接和IP地址配置
2. 如果看不到发送端，检查信令服务器是否正常运行
3. 如果视频不显示，检查WebRTC配置和权限

## 注意事项
- 这是一个简化版本，仅支持基本的屏幕共享功能
- 实际使用中需要添加更多的错误处理和用户界面优化
- 生产环境需要添加安全认证和加密功能 