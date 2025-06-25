@echo off
echo WebRTC信令服务器启动脚本
echo ============================

echo 安装依赖...
call npm install

echo.
echo 选择要启动的服务器版本:
echo 1. 基本版本 (server.js)
echo 2. 高级版本 (server_advanced.js)
echo.

set /p choice=请输入选择 (1/2): 

if "%choice%"=="1" (
  echo.
  echo 启动基本版本服务器...
  node server.js
) else if "%choice%"=="2" (
  echo.
  echo 启动高级版本服务器...
  node server_advanced.js
) else (
  echo.
  echo 无效选择，默认启动基本版本...
  node server.js
)

pause 