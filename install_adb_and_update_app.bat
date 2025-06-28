@echo off
echo 正在下载 ADB 工具...

REM 创建临时目录
if not exist "temp_adb" mkdir temp_adb
cd temp_adb

REM 下载 platform-tools (包含 adb)
powershell -Command "& {Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile 'platform-tools.zip'}"

echo 正在解压 ADB 工具...
powershell -Command "& {Expand-Archive -Path 'platform-tools.zip' -DestinationPath '.' -Force}"

echo 检查设备连接...
platform-tools\adb.exe devices

echo.
echo 正在安装应用...
platform-tools\adb.exe install -r "..\android_combined\app\build\outputs\apk\debug\app-debug.apk"

echo.
echo 安装完成！
echo 清理临时文件...
cd ..
rmdir /s /q temp_adb

pause 