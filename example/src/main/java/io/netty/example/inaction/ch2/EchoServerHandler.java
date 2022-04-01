package io.netty.example.inaction.ch2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.util.CharsetUtil;

/**
 * ch2:处理器实现
 *
 * @author YJ
 * @date 2022/1/5
 **/
// 此注解标识一个ChannelHandler可以被多个Channel安全的共享
@Sharable
public class EchoServerHandler extends ChannelInboundHandlerAdapter {

    /**
     * 这里接收到客户端发送过来到信息并打印出来
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf in = (ByteBuf) msg;
        EventLoop eventExecutors = ctx.channel().eventLoop();
        System.out.println("Server received: " + in.toString(CharsetUtil.UTF_8));
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.writeAndFlush(Unpooled.copiedBuffer("服务端向你问好", CharsetUtil.UTF_8))
            .addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
