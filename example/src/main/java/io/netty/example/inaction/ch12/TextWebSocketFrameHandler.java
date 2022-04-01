package io.netty.example.inaction.ch12;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.ServerHandshakeStateEvent;

/**
 * 处理WebSocket文本信息
 *
 * @author YJ
 * @date 2022/3/31
 **/
public class TextWebSocketFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final ChannelGroup group;

    public TextWebSocketFrameHandler(ChannelGroup group) {
        this.group = group;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg)
        throws Exception {
        // 增加消息的引用计数，并将它写到 ChannelGroup 中所有已经连接的客户端
        group.writeAndFlush(msg.retain());
    }

    /**
     * 重写 userEventTriggered() 方法以处理自定义事件
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 通知所有已经连接的 WebSocket 客户端新的客户端已经连接上了
        if (evt == ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            // 如果该事件表示握手成 功，则从该 Channelipeline 中移除 HttpRequestHandler
            // 因为将不会接收到任何 HTTP 消息了
            ctx.pipeline().remove(HTTPRequestHandler.class);
            // 通知所有已经连接的 WebSocket 客户端新的客户端已经连接上了
            group.writeAndFlush(new TextWebSocketFrame("Client " + ctx.channel() + " joined"));
            //  将新的 WebSocket Channel 添加到 ChannelGroup 中，以便它可以接收到所有的消息
            group.add(ctx.channel());
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
