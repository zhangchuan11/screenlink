const WebSocket = require('ws');

// 创建WebSocket服务器，监听6060端口
const wss = new WebSocket.Server({ port: 6060 });

// 存储所有连接的客户端
const clients = new Set();

// 记录客户端角色（发送者或接收者）
const clientRoles = new Map(); // 用于存储客户端角色

// 存储发送端信息（客户端就是发送端）
const senders = new Map(); // senderId -> {ws, name, offer, timestamp}
let nextSenderId = 1;

// 存储接收端信息
const receivers = new Map(); // receiverId -> {ws, selectedSenderId}
let nextReceiverId = 1;

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
    
    // 立即发送当前状态信息
    setTimeout(() => {
        // 发送发送端列表
        sendSenderList(ws);
    }, 1000); // 延迟1秒发送，确保连接稳定
    
    // 当收到客户端消息时
    ws.on('message', (message) => {
        try {
            // 打印原始消息内容
            console.log(`收到原始消息: ${message.toString()}`);
            
            const data = JSON.parse(message);
            console.log(`收到消息类型: ${data.type}`);
            
            // 新增：处理发送端注册（客户端就是发送端）
            if (data.type === 'register_sender' || data.type === 'register_client') {
                // 先查找并移除同名的旧发送端
                for (const [id, sender] of senders) {
                    if (sender.name === data.name) {
                        senders.delete(id);
                        if (sender.ws && sender.ws.readyState === WebSocket.OPEN) {
                            sender.ws.close();
                        }
                        console.log(`检测到同名发送端[${data.name}]，已移除旧的 senderId=${id}`);
                    }
                }

                const senderId = nextSenderId++;
                const senderName = data.name || `发送端${senderId}`;
                
                console.log(`新发送端注册: ${senderName} (ID: ${senderId})`);
                
                clientRoles.set(ws, 'sender');
                senders.set(senderId, {
                    ws: ws,
                    name: senderName,
                    offer: null,
                    timestamp: Date.now(),
                    id: senderId
                });
                
                // 给发送端分配ID
                ws.senderId = senderId;
                
                // 发送确认消息给发送端，包含分配的senderId
                ws.send(JSON.stringify({
                    type: 'sender_registered',
                    senderId: senderId,
                    name: senderName
                }));
                
                // 立即广播发送端列表更新
                broadcastSenderList();
                return;
            }
            
            // 新增：处理连接请求
            if (data.type === 'connect_request') {
                const sourceClientId = data.sourceClientId;
                const targetClientId = data.targetClientId;
                console.log(`收到连接请求: 客户端${sourceClientId} -> 客户端${targetClientId}`);
                
                // 查找目标发送端
                const targetSender = senders.get(targetClientId);
                if (targetSender && targetSender.ws.readyState === WebSocket.OPEN) {
                    targetSender.ws.send(JSON.stringify({
                        type: 'connect_request',
                        sourceClientId: sourceClientId
                    }));
                    console.log(`连接请求已转发给发送端${targetClientId}`);
                } else {
                    console.log(`目标发送端${targetClientId}不可用`);
                    ws.send(JSON.stringify({
                        type: 'error',
                        message: '目标发送端不可用'
                    }));
                }
                return;
            }
            
            // 如果是offer，标记该客户端为发送者并保存offer
            if (data.type === 'offer') {
                // 检查发送端是否已经注册
                let senderId = ws.senderId;
                let senderName = data.senderName || `发送端${senderId || 'unknown'}`;
                
                if (senderId && senders.has(senderId)) {
                    // 发送端已经注册，更新offer
                    const sender = senders.get(senderId);
                    sender.offer = data;
                    sender.timestamp = Date.now();
                    console.log(`更新已注册发送端${senderId}的offer`);
                } else {
                    // 发送端未注册，创建新的发送端
                    senderId = nextSenderId++;
                    senderName = data.senderName || `发送端${senderId}`;
                    
                    console.log(`收到offer消息，发送端名称: ${senderName}`);
                    console.log(`当前已连接的客户端数量: ${clients.size}`);
                    console.log(`当前已注册的发送端数量: ${senders.size}`);
                    
                    clientRoles.set(ws, 'sender');
                    senders.set(senderId, {
                        ws: ws,
                        name: senderName,
                        offer: data,
                        timestamp: Date.now(),
                        id: senderId
                    });
                    
                    // 给发送端分配ID
                    ws.senderId = senderId;
                    
                    console.log(`标记客户端为发送者，ID: ${senderId}, 名称: ${senderName}`);
                }
                
                console.log(`保存的offer内容: ${JSON.stringify(data).substring(0, 100)}...`);
                console.log(`注册后发送端数量: ${senders.size}`);
                
                // 通知所有接收端有新的发送端可用
                broadcastSenderList();
                
                // 如果有targetClientId，直接转发给目标发送端
                if (data.targetClientId) {
                    const targetSender = senders.get(data.targetClientId);
                    if (targetSender && targetSender.ws.readyState === WebSocket.OPEN) {
                        // 在offer消息中添加senderId信息
                        const offerWithSenderId = {
                            ...data,
                            senderId: senderId  // 添加senderId字段
                        };
                        targetSender.ws.send(JSON.stringify(offerWithSenderId));
                        console.log(`Offer已直接转发给目标发送端${data.targetClientId}，包含senderId: ${senderId}`);
                    } else {
                        console.log(`目标发送端${data.targetClientId}不可用，无法转发offer`);
                    }
                }
            }
            
            // 如果是answer，标记该客户端为接收者
            if (data.type === 'answer') {
                const receiverId = nextReceiverId++;
                const selectedSenderId = data.selectedSenderId;
                
                clientRoles.set(ws, 'receiver');
                receivers.set(receiverId, {
                    ws: ws,
                    selectedSenderId: selectedSenderId,
                    id: receiverId
                });
                
                // 给接收端分配ID
                ws.receiverId = receiverId;
                
                console.log(`标记客户端为接收者，ID: ${receiverId}, 选择的发送端: ${selectedSenderId}`);
                console.log(`收到answer: ${JSON.stringify(data).substring(0, 100)}...`);
                
                // 将answer转发给对应的发送端
                if (selectedSenderId && senders.has(selectedSenderId)) {
                    const sender = senders.get(selectedSenderId);
                    if (sender && sender.ws.readyState === WebSocket.OPEN) {
                        sender.ws.send(message.toString());
                        console.log(`接收端${receiverId}的answer已转发给发送端${selectedSenderId}`);
                    }
                    
                    // 不清除offer，让发送端可以重复使用
                    // sender.offer = null;
                }
                
                // 如果有targetClientId，直接转发给目标发送端
                if (data.targetClientId) {
                    const targetSender = senders.get(data.targetClientId);
                    if (targetSender && targetSender.ws.readyState === WebSocket.OPEN) {
                        targetSender.ws.send(message.toString());
                        console.log(`Answer已直接转发给目标发送端${data.targetClientId}`);
                    }
                }
            }
            
            // 如果是请求发送端列表
            if (data.type === 'request_senders') {
                // 如果客户端还没有被标记为接收端，先标记为接收端
                if (!clientRoles.has(ws)) {
                    const receiverId = nextReceiverId++;
                    clientRoles.set(ws, 'receiver');
                    receivers.set(receiverId, {
                        ws: ws,
                        selectedSenderId: null,
                        id: receiverId
                    });
                    ws.receiverId = receiverId;
                    console.log(`客户端请求发送端列表，标记为接收端，ID: ${receiverId}`);
                }
                sendSenderList(ws);
            }
            
            // 兼容客户端的 request_sender_list 消息
            if (data.type === 'request_sender_list') {
                console.log('收到 request_sender_list 消息，兼容处理');
                
                // 如果客户端还没有被标记为接收端，先标记为接收端
                if (!clientRoles.has(ws)) {
                    const receiverId = nextReceiverId++;
                    clientRoles.set(ws, 'receiver');
                    receivers.set(receiverId, {
                        ws: ws,
                        selectedSenderId: null,
                        id: receiverId
                    });
                    ws.receiverId = receiverId;
                    console.log(`客户端请求发送端列表，标记为接收端，ID: ${receiverId}`);
                }
                
                sendSenderList(ws);
            }
            
            // 如果是选择发送端
            if (data.type === 'select_sender') {
                const senderId = data.senderId;
                console.log(`客户端选择发送端: ${senderId}`);
                
                if (senders.has(senderId)) {
                    const sender = senders.get(senderId);
                    if (sender.offer) {
                        // 发送offer给接收端，并添加senderId字段
                        const offerWithSenderId = {
                            ...sender.offer,
                            senderId: senderId  // 添加senderId字段
                        };
                        const offerJson = JSON.stringify(offerWithSenderId);
                        ws.send(offerJson);
                        console.log(`发送端${senderId}的offer已发送给接收端: ${offerJson.substring(0, 100)}...`);
                        
                        // 主动推送连接状态更新
                        broadcastConnectionStatus(senderId, 'connected');
                    } else {
                        // 发送错误消息
                        console.log(`发送端${senderId}没有可用的offer`);
                        ws.send(JSON.stringify({
                            type: 'error',
                            message: '选择的发送端不可用'
                        }));
                        
                        // 主动推送连接状态更新
                        broadcastConnectionStatus(senderId, 'unavailable');
                    }
                } else {
                    // 发送错误消息
                    console.log(`发送端${senderId}不存在`);
                    ws.send(JSON.stringify({
                        type: 'error',
                        message: '选择的发送端不存在'
                    }));
                    
                    // 主动推送连接状态更新
                    broadcastConnectionStatus(senderId, 'not_found');
                }
            }
            
            // 如果是ICE候选
            if (data.type === 'ice') {
                console.log(`收到ICE候选: ${data.candidate}`);
                
                // 转发给所有其他客户端
                for (const client of clients) {
                    if (client !== ws && client.readyState === WebSocket.OPEN) {
                        client.send(message.toString());
                    }
                }
            }
            
            // 如果是心跳消息
            if (data.type === 'heartbeat') {
                console.log(`收到心跳消息: ${data.timestamp || Date.now()}`);
                
                // 发送心跳响应
                ws.send(JSON.stringify({
                    type: 'heartbeat_ack',
                    timestamp: Date.now()
                }));
                
                // 更新发送端时间戳
                if (ws.senderId && senders.has(ws.senderId)) {
                    const sender = senders.get(ws.senderId);
                    sender.lastHeartbeat = Date.now();
                }
            }
            
            // 将其他消息转发给相应的客户端
            if (data.type === 'candidate' || data.type === 'ice_candidate' || data.type === 'ice') {
                console.log('candidate内容:', JSON.stringify(data));
                const senderRole = clientRoles.get(ws);
                console.log(`收到candidate消息，发送者角色: ${senderRole}`);
                
                if (senderRole === 'sender') {
                    // 发送端发送的candidate，转发给选择该发送端的接收端
                    let forwardedToAny = false;
                    for (const [receiverId, receiver] of receivers) {
                        if (receiver.selectedSenderId === ws.senderId) {
                            receiver.ws.send(message.toString());
                            console.log(`发送端${ws.senderId}的candidate已转发给接收端${receiverId}`);
                            forwardedToAny = true;
                            break;
                        }
                    }
                    if (!forwardedToAny) {
                        console.log(`未找到选择发送端${ws.senderId}的接收端，无法转发candidate`);
                    }
                } else if (senderRole === 'receiver') {
                    // 接收端发送的candidate，转发给对应的发送端
                    const receiver = receivers.get(ws.receiverId);
                    if (receiver && receiver.selectedSenderId) {
                        const sender = senders.get(receiver.selectedSenderId);
                        if (sender && sender.ws.readyState === WebSocket.OPEN) {
                            sender.ws.send(message.toString());
                            console.log(`接收端${ws.receiverId}的candidate已转发给发送端${receiver.selectedSenderId}`);
                        } else {
                            console.log(`发送端${receiver.selectedSenderId}不可用，无法转发candidate`);
                        }
                    } else {
                        console.log(`接收端${ws.receiverId}未选择发送端，无法转发candidate`);
                    }
                } else {
                    console.log(`未知角色的客户端发送candidate: ${senderRole}`);
                }
            }
        } catch (e) {
            console.error('处理消息时出错:', e);
        }
    });
    
    // 当客户端断开连接时
    ws.on('close', () => {
        console.log(`客户端断开连接: ${clientIp}`);
        console.log(`断开前客户端角色: ${clientRoles.get(ws)}`);
        console.log(`断开前发送端ID: ${ws.senderId}`);
        console.log(`断开前接收端ID: ${ws.receiverId}`);
        console.log(`断开前已连接客户端数量: ${clients.size}`);
        console.log(`断开前已注册发送端数量: ${senders.size}`);
        
        clients.delete(ws);
        
        // 如果是发送者断开连接，清除相关信息
        if (clientRoles.get(ws) === 'sender' && ws.senderId) {
            senders.delete(ws.senderId);
            console.log(`发送端${ws.senderId}断开连接，已清除`);
            console.log(`清除后发送端数量: ${senders.size}`);
            broadcastSenderList();
            broadcastConnectionStatus(ws.senderId, 'disconnected');
        }
        
        // 如果是接收者断开连接，清除相关信息
        if (clientRoles.get(ws) === 'receiver' && ws.receiverId) {
            receivers.delete(ws.receiverId);
            console.log(`接收端${ws.receiverId}断开连接，已清除`);
        }
        
        clientRoles.delete(ws);
        console.log(`断开后已连接客户端数量: ${clients.size}`);
    });
    
    // 处理错误
    ws.on('error', (error) => {
        console.error(`客户端错误: ${error.message}`);
        clients.delete(ws);
        
        // 如果是发送者出错，清除相关信息
        if (clientRoles.get(ws) === 'sender' && ws.senderId) {
            senders.delete(ws.senderId);
            broadcastSenderList();
            broadcastConnectionStatus(ws.senderId, 'disconnected');
        }
        
        // 如果是接收者出错，清除相关信息
        if (clientRoles.get(ws) === 'receiver' && ws.receiverId) {
            receivers.delete(ws.receiverId);
        }
        
        clientRoles.delete(ws);
    });
});

/**
 * 发送发送端列表给指定客户端
 */
function sendSenderList(ws) {
    console.log(`准备发送发送端列表，当前发送端Map大小: ${senders.size}`);
    console.log(`发送端Map内容:`, Array.from(senders.entries()).map(([id, sender]) => ({
        id: id,
        name: sender.name,
        hasOffer: sender.offer !== null,
        timestamp: sender.timestamp
    })));
    
    const senderList = Array.from(senders.values()).map(sender => ({
        id: sender.id,
        name: sender.name,
        timestamp: sender.timestamp,
        available: sender.offer !== null
    }));
    
    ws.send(JSON.stringify({
        type: 'sender_list',
        senders: senderList
    }));
    
    console.log(`发送端列表已发送，共${senderList.length}个发送端`);
    console.log(`发送的列表内容:`, senderList);
}

/**
 * 广播发送端列表给所有客户端（包括发送端和接收端）
 */
function broadcastSenderList() {
    const senderList = Array.from(senders.values()).map(sender => ({
        id: sender.id,
        name: sender.name,
        timestamp: sender.timestamp,
        available: sender.offer !== null
    }));
    
    // 向所有接收端广播
    for (const [receiverId, receiver] of receivers) {
        if (receiver.ws.readyState === WebSocket.OPEN) {
            receiver.ws.send(JSON.stringify({
                type: 'sender_list',
                senders: senderList
            }));
        }
    }
    
    // 向所有发送端广播（除了自己）
    for (const [senderId, sender] of senders) {
        if (sender.ws.readyState === WebSocket.OPEN) {
            sender.ws.send(JSON.stringify({
                type: 'sender_list',
                senders: senderList
            }));
        }
    }
    
    console.log(`发送端列表已广播给所有客户端，共${senderList.length}个发送端`);
}

// 定期清理过期的发送端（超过5分钟没有心跳的）
setInterval(() => {
    const now = Date.now();
    const expiredSenders = [];
    
    for (const [senderId, sender] of senders) {
        if (now - sender.timestamp > 5 * 60 * 1000) { // 5分钟
            expiredSenders.push(senderId);
        }
    }
    
    for (const senderId of expiredSenders) {
        const sender = senders.get(senderId);
        if (sender && sender.ws.readyState === WebSocket.OPEN) {
            sender.ws.close();
        }
        senders.delete(senderId);
        console.log(`发送端${senderId}因超时被清理`);
    }
    
    if (expiredSenders.length > 0) {
        broadcastSenderList();
    }
}, 30000); // 每30秒检查一次

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

/**
 * 广播连接状态更新给所有客户端
 */
function broadcastConnectionStatus(senderId, status) {
    const statusMessage = {
        type: 'connection_status',
        senderId: senderId,
        status: status,
        timestamp: Date.now()
    };
    
    // 广播给所有接收端
    for (const receiver of receivers.values()) {
        if (receiver.ws.readyState === WebSocket.OPEN) {
            receiver.ws.send(JSON.stringify(statusMessage));
        }
    }
    
    // 广播给所有发送端
    for (const sender of senders.values()) {
        if (sender.ws.readyState === WebSocket.OPEN) {
            sender.ws.send(JSON.stringify(statusMessage));
        }
    }
    
    console.log(`连接状态已广播给所有客户端: 发送端${senderId} - ${status}`);
} 