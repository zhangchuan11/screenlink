@echo off
echo 测试视频调试功能
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
echo 调试说明:
echo - 查看日志中的 "SDP中包含视频轨道" 消息
echo - 查看日志中的 "视频帧已捕获" 消息
echo - 查看日志中的 "收到视频帧" 消息
echo - 查看日志中的 "EglRenderer" 消息
echo.
echo 如果看到 "Frames received: 0"，说明视频帧没有到达接收端
echo 如果看到视频帧捕获消息但没有接收消息，说明网络传输有问题
echo.
pause 