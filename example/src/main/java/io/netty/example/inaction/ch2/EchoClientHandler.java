package io.netty.example.inaction.ch2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.CharsetUtil;

/**
 * 客户端处理类
 *
 * @author YJ
 * @date 2022/1/5
 **/
public class EchoClientHandler extends SimpleChannelInboundHandler<ByteBuf> {

    /**
     * 此方法将在一个连接建立时被调用，会将相关信息发送给服务端
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.writeAndFlush(Unpooled.copiedBuffer("客户端向你问好!", CharsetUtil.UTF_8));
    }

    /**
     * 每当接收到数据时，都会调用此方法，注意：由服务器发送都消息可能会被分块接收，也就是
     * 说此方法可能会被调用多次
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        System.out.println("Client received: " + in.toString(CharsetUtil.UTF_8));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();;
        ctx.close();
    }
}
