#!/bin/bash

echo "WebRTC信令服务器启动脚本"
echo "============================"

echo "安装依赖..."
npm install

echo ""
echo "选择要启动的服务器版本:"
echo "1. 基本版本 (server.js)"
echo "2. 高级版本 (server_advanced.js)"
echo ""

read -p "请输入选择 (1/2): " choice

if [ "$choice" = "1" ]; then
  echo ""
  echo "启动基本版本服务器..."
  node server.js
elif [ "$choice" = "2" ]; then
  echo ""
  echo "启动高级版本服务器..."
  node server_advanced.js
else
  echo ""
  echo "无效选择，默认启动基本版本..."
  node server.js
fi 