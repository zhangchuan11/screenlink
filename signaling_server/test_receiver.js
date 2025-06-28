const WebSocket = require('ws');

// 连接到信令服务器
const ws = new WebSocket('ws://localhost:6060');

ws.on('open', function open() {
    console.log('已连接到信令服务器');
    
    // 连接成功后立即请求发送端列表
    const requestMessage = {
        type: 'request_senders'
    };
    
    console.log('发送请求发送端列表消息:', JSON.stringify(requestMessage));
    ws.send(JSON.stringify(requestMessage));
});

ws.on('message', function message(data) {
    console.log('收到消息:', data.toString());
    
    try {
        const json = JSON.parse(data.toString());
        
        if (json.type === 'sender_list') {
            console.log('收到发送端列表:');
            console.log('发送端数量:', json.senders.length);
            
            if (json.senders.length === 0) {
                console.log('当前没有可用的发送端');
            } else {
                json.senders.forEach((sender, index) => {
                    console.log(`发送端 ${index + 1}: ID=${sender.id}, 名称=${sender.name}, 可用=${sender.available}`);
                });
            }
        } else if (json.type === 'info') {
            console.log('服务器信息:', json.message);
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

// 5秒后关闭连接
setTimeout(() => {
    console.log('测试完成，关闭连接');
    ws.close();
    process.exit(0);
}, 5000); 