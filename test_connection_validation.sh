#!/bin/bash

# WebSocket连接和ICE连接修复验证脚本

echo "=== WebSocket连接和ICE连接修复验证 ==="

# 1. 检查信令服务器状态
echo "1. 检查信令服务器状态..."
if pgrep -f "python.*server.py" > /dev/null; then
    echo "✅ 信令服务器正在运行"
else
    echo "❌ 信令服务器未运行，请先启动服务器"
    echo "cd signaling_server && python server.py"
    exit 1
fi

# 2. 检查Android设备连接
echo "2. 检查Android设备连接..."
if adb devices | grep -q "device$"; then
    echo "✅ Android设备已连接"
else
    echo "❌ 未检测到Android设备，请连接设备并启用USB调试"
    exit 1
fi

# 3. 安装应用（如果需要）
echo "3. 安装应用..."
cd screenlink_new
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. 启动应用并监控日志
echo "4. 启动应用并监控连接日志..."
adb shell am start -n com.screenlink.newapp/.MainActivity

# 5. 监控关键日志
echo "5. 监控连接相关日志（按Ctrl+C停止）..."
echo "=== 连接日志监控 ==="
adb logcat | grep -E "(ScreenShareService|MainActivity)" | grep -E "(连接|WebSocket|ICE|错误|超时|重连)" --color=always

# 6. 测试说明
echo ""
echo "=== 测试说明 ==="
echo "1. 在应用中输入服务器地址：192.168.1.2:6060"
echo "2. 观察连接是否成功建立"
echo "3. 启动屏幕采集，观察ICE连接状态"
echo "4. 测试错误场景（错误地址、断开网络等）"
echo "5. 验证错误提示和重连机制"

echo ""
echo "=== 预期修复效果 ==="
echo "✅ 连接间隔控制：不会频繁尝试连接"
echo "✅ 错误类型分析：提供具体的错误提示"
echo "✅ ICE连接超时：20秒内完成或超时重试"
echo "✅ 资源清理：断开时正确清理所有资源"
echo "✅ 智能重连：失败时按递增延迟重连" 