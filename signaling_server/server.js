const WebSocket = require('ws');

// 创建WebSocket服务器，监听6060端口
const wss = new WebSocket.Server({ port: 6060 });

// 存储所有连接的客户端
const clients = new Set();

// 记录客户端角色（发送者或接收者）
const clientRoles = new Map(); // 用于存储客户端角色

console.log('信令服务器已启动，监听端口: 6060');

// 当有新的客户端连接时
wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    console.log(`新客户端连接: ${clientIp}`);
    
    // 将新客户端添加到集合中
    clients.add(ws);
    
    // 发送欢迎消息
    ws.send(JSON.stringify({
        type: 'info',
        message: '已连接到信令服务器'
    }));
    
    // 当收到客户端消息时
    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            console.log(`收到消息类型: ${data.type}`);
            
            // 如果是offer，标记该客户端为发送者
            if (data.type === 'offer') {
                clientRoles.set(ws, 'sender');
                console.log('标记客户端为发送者');
            }
            
            // 如果是answer，标记该客户端为接收者
            if (data.type === 'answer') {
                clientRoles.set(ws, 'receiver');
                console.log('标记客户端为接收者');
            }
            
            // 将消息转发给其他所有客户端
            for (const client of clients) {
                // 不要发送给自己
                if (client !== ws && client.readyState === WebSocket.OPEN) {
                    // 发送者的消息只发给接收者，接收者的消息只发给发送者
                    const senderRole = clientRoles.get(ws);
                    const receiverRole = clientRoles.get(client);
                    
                    // 如果角色互补，或者尚未确定角色，则转发消息
                    if (!senderRole || !receiverRole || 
                        (senderRole === 'sender' && receiverRole === 'receiver') || 
                        (senderRole === 'receiver' && receiverRole === 'sender')) {
                        client.send(message.toString());
                        console.log(`消息已转发给 ${clientRoles.get(client) || '未知角色'} 客户端`);
                    }
                }
            }
        } catch (e) {
            console.error('处理消息时出错:', e);
        }
    });
    
    // 当客户端断开连接时
    ws.on('close', () => {
        console.log(`客户端断开连接: ${clientIp}`);
        clients.delete(ws);
        clientRoles.delete(ws);
    });
    
    // 处理错误
    ws.on('error', (error) => {
        console.error(`客户端错误: ${error.message}`);
        clients.delete(ws);
        clientRoles.delete(ws);
    });
});

// 处理服务器错误
wss.on('error', (error) => {
    console.error(`服务器错误: ${error.message}`);
});

// 优雅地关闭服务器
process.on('SIGINT', () => {
    console.log('关闭服务器...');
    
    wss.close(() => {
        console.log('服务器已关闭');
        process.exit(0);
    });
}); 