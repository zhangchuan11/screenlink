#!/bin/bash

echo "正在编译 ScreenLinkNew 项目..."

# 切换到脚本所在目录
cd "$(dirname "$0")"

echo "清理项目..."
./gradlew clean

echo "编译Debug版本..."
./gradlew assembleDebug

if [ $? -ne 0 ]; then
    echo "编译失败！"
    exit 1
fi

echo "编译成功！"

read -p "是否要安装到设备？(y/N): " choice
if [[ $choice =~ ^[Yy]$ ]]; then
    echo "正在安装到设备..."
    ./gradlew installDebug
    
    if [ $? -ne 0 ]; then
        echo "安装失败！请确保设备已连接并启用USB调试"
    else
        echo "安装成功！"
    fi
fi

echo "完成！" 