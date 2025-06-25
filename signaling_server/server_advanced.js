const WebSocket = require('ws');
const http = require('http');
const url = require('url');

// 创建HTTP服务器
const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('WebRTC信令服务器正在运行\n');
});

// 在HTTP服务器上创建WebSocket服务器
const wss = new WebSocket.Server({ server });

// 存储房间信息
const rooms = new Map();

// 记录客户端信息
const clients = new Map();

// 服务器启动时间
const startTime = new Date();

// 监听端口
const PORT = process.env.PORT || 6060;

// 启动服务器
server.listen(PORT, () => {
    console.log(`信令服务器已启动，监听端口: ${PORT}`);
    console.log(`启动时间: ${startTime.toLocaleString()}`);
});

// 当有新的客户端连接时
wss.on('connection', (ws, req) => {
    // 获取客户端IP和查询参数
    const clientIp = req.socket.remoteAddress;
    const queryParams = url.parse(req.url, true).query;
    const roomId = queryParams.room || 'default';
    const clientId = generateClientId();
    
    console.log(`新客户端连接: ${clientIp}, 客户端ID: ${clientId}, 房间: ${roomId}`);
    
    // 记录客户端信息
    clients.set(ws, {
        id: clientId,
        ip: clientIp,
        room: roomId,
        role: null,
        connectTime: new Date()
    });
    
    // 将客户端添加到房间
    if (!rooms.has(roomId)) {
        rooms.set(roomId, new Set());
    }
    rooms.get(roomId).add(ws);
    
    // 发送欢迎消息
    sendToClient(ws, {
        type: 'info',
        message: '已连接到信令服务器',
        clientId: clientId,
        room: roomId,
        timestamp: new Date().toISOString()
    });
    
    // 当收到客户端消息时
    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            const clientInfo = clients.get(ws);
            
            console.log(`收到来自客户端 ${clientInfo.id} 的消息类型: ${data.type}`);
            
            // 处理不同类型的消息
            switch (data.type) {
                case 'offer':
                    clientInfo.role = 'sender';
                    console.log(`客户端 ${clientInfo.id} 被标记为发送者`);
                    break;
                    
                case 'answer':
                    clientInfo.role = 'receiver';
                    console.log(`客户端 ${clientInfo.id} 被标记为接收者`);
                    break;
                    
                case 'join':
                    // 处理加入房间请求
                    const newRoom = data.room || roomId;
                    if (newRoom !== clientInfo.room) {
                        // 从旧房间移除
                        if (rooms.has(clientInfo.room)) {
                            rooms.get(clientInfo.room).delete(ws);
                            if (rooms.get(clientInfo.room).size === 0) {
                                rooms.delete(clientInfo.room);
                            }
                        }
                        
                        // 添加到新房间
                        if (!rooms.has(newRoom)) {
                            rooms.set(newRoom, new Set());
                        }
                        rooms.get(newRoom).add(ws);
                        
                        // 更新客户端信息
                        clientInfo.room = newRoom;
                        console.log(`客户端 ${clientInfo.id} 加入房间: ${newRoom}`);
                        
                        // 通知客户端
                        sendToClient(ws, {
                            type: 'room_joined',
                            room: newRoom,
                            timestamp: new Date().toISOString()
                        });
                    }
                    return;
                    
                case 'ping':
                    // 处理ping请求
                    sendToClient(ws, {
                        type: 'pong',
                        timestamp: new Date().toISOString()
                    });
                    return;
            }
            
            // 将消息转发给同一房间的其他客户端
            if (rooms.has(clientInfo.room)) {
                for (const client of rooms.get(clientInfo.room)) {
                    // 不要发送给自己
                    if (client !== ws && client.readyState === WebSocket.OPEN) {
                        const receiverInfo = clients.get(client);
                        
                        // 发送者的消息只发给接收者，接收者的消息只发给发送者
                        if (!clientInfo.role || !receiverInfo.role || 
                            (clientInfo.role === 'sender' && receiverInfo.role === 'receiver') || 
                            (clientInfo.role === 'receiver' && receiverInfo.role === 'sender')) {
                            
                            // 添加额外信息到消息中
                            const enhancedData = {
                                ...data,
                                _meta: {
                                    fromClientId: clientInfo.id,
                                    timestamp: new Date().toISOString()
                                }
                            };
                            
                            sendToClient(client, enhancedData);
                            console.log(`消息已转发给客户端 ${receiverInfo.id} (${receiverInfo.role || '未知角色'})`);
                        }
                    }
                }
            }
        } catch (e) {
            console.error('处理消息时出错:', e);
            sendToClient(ws, {
                type: 'error',
                message: '处理消息时出错',
                error: e.message,
                timestamp: new Date().toISOString()
            });
        }
    });
    
    // 当客户端断开连接时
    ws.on('close', () => {
        const clientInfo = clients.get(ws);
        if (clientInfo) {
            console.log(`客户端断开连接: ${clientInfo.id}, IP: ${clientInfo.ip}, 房间: ${clientInfo.room}`);
            
            // 从房间中移除客户端
            if (rooms.has(clientInfo.room)) {
                rooms.get(clientInfo.room).delete(ws);
                
                // 如果房间为空，删除房间
                if (rooms.get(clientInfo.room).size === 0) {
                    rooms.delete(clientInfo.room);
                    console.log(`房间 ${clientInfo.room} 已被删除（无客户端）`);
                } else {
                    // 通知房间中的其他客户端
                    for (const client of rooms.get(clientInfo.room)) {
                        if (client.readyState === WebSocket.OPEN) {
                            sendToClient(client, {
                                type: 'peer_disconnected',
                                clientId: clientInfo.id,
                                timestamp: new Date().toISOString()
                            });
                        }
                    }
                }
            }
            
            // 删除客户端信息
            clients.delete(ws);
        }
    });
    
    // 处理错误
    ws.on('error', (error) => {
        const clientInfo = clients.get(ws);
        console.error(`客户端错误: ${clientInfo ? clientInfo.id : '未知'}, 错误: ${error.message}`);
        
        // 从房间和客户端列表中移除
        if (clientInfo && rooms.has(clientInfo.room)) {
            rooms.get(clientInfo.room).delete(ws);
            if (rooms.get(clientInfo.room).size === 0) {
                rooms.delete(clientInfo.room);
            }
        }
        clients.delete(ws);
    });
});

// 处理服务器错误
wss.on('error', (error) => {
    console.error(`服务器错误: ${error.message}`);
});

// 生成唯一的客户端ID
function generateClientId() {
    return `client_${Math.random().toString(36).substr(2, 9)}`;
}

// 向客户端发送消息
function sendToClient(client, data) {
    if (client.readyState === WebSocket.OPEN) {
        try {
            client.send(JSON.stringify(data));
        } catch (e) {
            console.error('发送消息失败:', e);
        }
    }
}

// 定期清理断开的连接
setInterval(() => {
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.CLOSING || client.readyState === WebSocket.CLOSED) {
            const clientInfo = clients.get(client);
            if (clientInfo) {
                console.log(`清理断开的客户端: ${clientInfo.id}`);
                
                if (rooms.has(clientInfo.room)) {
                    rooms.get(clientInfo.room).delete(client);
                    if (rooms.get(clientInfo.room).size === 0) {
                        rooms.delete(clientInfo.room);
                    }
                }
                clients.delete(client);
            }
        }
    });
}, 30000); // 每30秒清理一次

// 服务器状态监控
setInterval(() => {
    const uptime = Math.floor((new Date() - startTime) / 1000);
    const totalClients = clients.size;
    const totalRooms = rooms.size;
    
    console.log(`服务器状态: 运行时间=${formatUptime(uptime)}, 客户端=${totalClients}, 房间=${totalRooms}`);
    
    // 输出每个房间的状态
    rooms.forEach((clients, roomId) => {
        console.log(`房间 ${roomId}: ${clients.size} 个客户端`);
    });
}, 60000); // 每60秒输出一次

// 格式化运行时间
function formatUptime(seconds) {
    const days = Math.floor(seconds / (3600 * 24));
    seconds %= (3600 * 24);
    const hours = Math.floor(seconds / 3600);
    seconds %= 3600;
    const minutes = Math.floor(seconds / 60);
    seconds %= 60;
    
    return `${days}天 ${hours}小时 ${minutes}分钟 ${seconds}秒`;
}

// 优雅地关闭服务器
process.on('SIGINT', () => {
    console.log('关闭服务器...');
    
    // 通知所有客户端
    clients.forEach((info, client) => {
        if (client.readyState === WebSocket.OPEN) {
            sendToClient(client, {
                type: 'server_shutdown',
                message: '服务器正在关闭',
                timestamp: new Date().toISOString()
            });
        }
    });
    
    // 关闭WebSocket服务器
    wss.close(() => {
        console.log('WebSocket服务器已关闭');
        
        // 关闭HTTP服务器
        server.close(() => {
            console.log('HTTP服务器已关闭');
            process.exit(0);
        });
    });
}); 