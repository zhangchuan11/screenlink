const WebSocket = require('ws');

// 测试发送端连接
function testSenderConnection() {
    console.log('开始测试发送端连接...');
    
    const ws = new WebSocket('ws://localhost:6060');
    
    ws.on('open', () => {
        console.log('WebSocket连接已建立');
        
        // 发送一个简单的offer消息
        const offer = {
            type: 'offer',
            sdp: 'v=0\r\no=- 1234567890 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\na=msid-semantic: WMS\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=sendonly\r\na=msid:video-track\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\na=ssrc:1234567890 cname:video-track\r\n',
            senderName: '测试发送端'
        };
        
        console.log('发送offer消息:', JSON.stringify(offer, null, 2));
        ws.send(JSON.stringify(offer));
    });
    
    ws.on('message', (data) => {
        console.log('收到服务器消息:', data.toString());
    });
    
    ws.on('close', (code, reason) => {
        console.log('WebSocket连接关闭:', code, reason);
    });
    
    ws.on('error', (error) => {
        console.error('WebSocket错误:', error);
    });
    
    // 5秒后关闭连接
    setTimeout(() => {
        console.log('测试完成，关闭连接');
        ws.close();
    }, 5000);
}

// 测试接收端连接
function testReceiverConnection() {
    console.log('\n开始测试接收端连接...');
    
    const ws = new WebSocket('ws://localhost:6060');
    
    ws.on('open', () => {
        console.log('WebSocket连接已建立');
        
        // 请求发送端列表
        const request = {
            type: 'request_senders'
        };
        
        console.log('请求发送端列表:', JSON.stringify(request, null, 2));
        ws.send(JSON.stringify(request));
    });
    
    ws.on('message', (data) => {
        console.log('收到服务器消息:', data.toString());
    });
    
    ws.on('close', (code, reason) => {
        console.log('WebSocket连接关闭:', code, reason);
    });
    
    ws.on('error', (error) => {
        console.error('WebSocket错误:', error);
    });
    
    // 3秒后关闭连接
    setTimeout(() => {
        console.log('测试完成，关闭连接');
        ws.close();
    }, 3000);
}

// 运行测试
if (require.main === module) {
    // 先测试发送端
    testSenderConnection();
    
    // 2秒后测试接收端
    setTimeout(() => {
        testReceiverConnection();
    }, 2000);
}

module.exports = { testSenderConnection, testReceiverConnection }; 