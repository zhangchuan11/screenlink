@echo off
echo 正在编译 ScreenLinkNew 项目...

cd /d "%~dp0"

echo 清理项目...
gradlew.bat clean

echo 编译Debug版本...
gradlew.bat assembleDebug

if %ERRORLEVEL% NEQ 0 (
    echo 编译失败！
    pause
    exit /b 1
)

echo 编译成功！

echo 是否要安装到设备？(Y/N)
set /p choice=
if /i "%choice%"=="Y" (
    echo 正在安装到设备...
    gradlew.bat installDebug
    
    if %ERRORLEVEL% NEQ 0 (
        echo 安装失败！请确保设备已连接并启用USB调试
    ) else (
        echo 安装成功！
    )
)

echo 完成！
pause 