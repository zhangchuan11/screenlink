@echo off
echo 测试修复后的视频流功能
echo.

echo 修复内容:
echo - 增强了信令服务器的日志和消息处理
echo - 修复了answer消息没有正确转发的问题
echo - 改进了ICE连接建立和监控机制
echo - 添加了连接断开时的自动重连功能
echo.

echo 1. 启动信令服务器...
cd signaling_server
start "信令服务器" cmd /k "node server.js"
cd ..

echo 2. 等待服务器启动...
timeout /t 3 /nobreak >nul

echo 3. 启动测试流程...
echo - 请先启动发送端应用，点击"开始投屏"
echo - 然后启动接收端应用，选择发送端
echo.

echo 4. 调试提示:
echo - 在信令服务器控制台查看"收到原始消息"，确认消息正确发送
echo - 在发送端日志中查看"ICE连接状态"，应该从NEW变为CONNECTED
echo - 在接收端日志中查看"收到轨道事件: video"和"视频流已接收并添加到SurfaceView"
echo.

echo 如果仍然没有显示视频，请检查:
echo 1. 两台设备的IP地址是否正确设置
echo 2. 信令服务器是否收到并转发了offer、answer和candidate消息
echo 3. 接收端日志中是否有"Frames received: 0"，说明帧没有到达
echo.

pause 