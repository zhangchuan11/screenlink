@echo off
echo 测试修复后的视频流功能
echo.

echo 修复内容:
echo - 移除了导致崩溃的VideoSink监听器
echo - 添加了安全的视频状态监控
echo - 增强了调试信息
echo.

echo 1. 启动信令服务器...
cd signaling_server
start "信令服务器" cmd /k "node server.js"
cd ..

echo 2. 等待服务器启动...
timeout /t 3 /nobreak >nul

echo 3. 启动发送端应用...
echo 请手动启动发送端应用并开始投屏

echo 4. 启动接收端应用...
echo 请手动启动接收端应用并选择发送端

echo.
echo 新的调试信息:
echo - 查看 "视频状态检查:" 消息，了解视频组件状态
echo - 查看 "SDP中包含视频轨道" 消息，确认SDP协商
echo - 查看 "ICE连接状态: CONNECTED" 消息，确认网络连接
echo - 查看 "收到轨道事件: video" 消息，确认轨道接收
echo.
echo 如果应用不再崩溃，说明修复成功
echo 如果仍有视频显示问题，请查看新的调试信息
echo.
pause 