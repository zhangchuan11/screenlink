const WebSocket = require('ws');

// 连接到信令服务器
const ws = new WebSocket('ws://localhost:6060');

ws.on('open', function open() {
    console.log('发送端已连接到信令服务器');
    
    // 模拟发送offer消息来注册为发送端
    const offerMessage = {
        type: 'offer',
        senderName: '测试发送端',
        sdp: 'v=0\r\no=- 1234567890 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\na=msid-semantic: WMS\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=sendonly\r\na=msid:msid0 0\r\na=rtpmap:96 VP8/90000\r\n'
    };
    
    console.log('发送offer消息:', JSON.stringify(offerMessage));
    ws.send(JSON.stringify(offerMessage));
});

ws.on('message', function message(data) {
    console.log('收到消息:', data.toString());
    
    try {
        const json = JSON.parse(data.toString());
        
        if (json.type === 'info') {
            console.log('服务器信息:', json.message);
        } else if (json.type === 'answer') {
            console.log('收到answer消息');
        } else if (json.type === 'candidate') {
            console.log('收到ICE候选消息');
        }
    } catch (e) {
        console.error('解析消息失败:', e);
    }
});

ws.on('close', function close() {
    console.log('连接已关闭');
});

ws.on('error', function error(err) {
    console.error('WebSocket错误:', err);
});

// 保持连接30秒
setTimeout(() => {
    console.log('测试完成，关闭连接');
    ws.close();
    process.exit(0);
}, 30000); 