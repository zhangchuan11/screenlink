@echo off
echo ========================================
echo 多发送端功能测试脚本
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
echo 2. 启动信令服务器...
start "信令服务器" cmd /k "cd signaling_server && node server.js"

echo.
echo 3. 等待服务器启动...
timeout /t 3 /nobreak >nul

echo.
echo 4. 编译Android应用...
cd android_combined
call gradlew assembleDebug

if errorlevel 1 (
    echo 编译失败，请检查错误信息
    pause
    exit /b 1
)

echo.
echo 5. 安装应用到设备...
adb install app/build/outputs/apk/debug/app-debug.apk

if errorlevel 1 (
    echo 安装失败，请确保设备已连接并启用USB调试
    pause
    exit /b 1
)

echo.
echo ========================================
echo 多发送端测试环境准备完成！
echo ========================================
echo.
echo 测试步骤：
echo.
echo 1. 在第一个设备上启动应用
echo    - 选择"发送端"
echo    - 输入名称："手机A"
echo    - 输入服务器地址：192.168.168.102:6060
echo    - 点击"开始投屏"
echo.
echo 2. 在第二个设备上启动应用
echo    - 选择"发送端"
echo    - 输入名称："手机B"
echo    - 输入服务器地址：192.168.168.102:6060
echo    - 点击"开始投屏"
echo.
echo 3. 在第三个设备上启动应用
echo    - 选择"接收端"
echo    - 应用会自动连接并显示发送端列表
echo    - 点击选择想要连接的发送端
echo.
echo 预期结果：
echo - 接收端能看到2个发送端
echo - 能正常选择并连接到任意发送端
echo - 画面流畅显示
echo.
pause 