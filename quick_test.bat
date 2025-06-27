@echo off
echo ========================================
echo ScreenLink 快速测试脚本
echo ========================================
echo.

echo 1. 检查Node.js是否安装...
node --version >nul 2>&1
if errorlevel 1 (
    echo 错误：未找到Node.js，请先安装Node.js
    pause
    exit /b 1
)
echo Node.js已安装

echo.
echo 2. 检查Java是否安装...
java -version >nul 2>&1
if errorlevel 1 (
    echo 错误：未找到Java，请先安装Java JDK
    pause
    exit /b 1
)
echo Java已安装

echo.
echo 3. 启动信令服务器...
start "信令服务器" cmd /k "cd signaling_server && node server.js"

echo.
echo 4. 等待服务器启动...
timeout /t 3 /nobreak >nul

echo.
echo 5. 编译Android应用...
cd android_combined
call gradlew assembleDebug

if errorlevel 1 (
    echo 编译失败，请检查错误信息
    pause
    exit /b 1
)

echo.
echo 6. 安装应用到设备...
adb install app/build/outputs/apk/debug/app-debug.apk

if errorlevel 1 (
    echo 安装失败，请确保设备已连接并启用USB调试
    pause
    exit /b 1
)

echo.
echo ========================================
echo 测试环境准备完成！
echo ========================================
echo.
echo 现在可以：
echo 1. 在设备上启动应用
echo 2. 先启动发送端，再启动接收端
echo 3. 或者先启动接收端，再启动发送端
echo.
echo 两种方式都应该能正常工作
echo.
pause 