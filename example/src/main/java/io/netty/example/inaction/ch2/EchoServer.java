package io.netty.example.inaction.ch2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 服务端
 *
 * @author YJ
 * @date 2022/1/5
 **/
public class EchoServer {

    public static void main(String[] args) throws Exception {
        // 创建两个线程组 boosGroup、workerGroup
        // boosGroup用于监听客户端连接，专门负责与客户端的连接，并把连接注册到
        // workGroup的Selector中，设置线程数为1
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // 用于处理每一个连接发生的读写事件
        EventLoopGroup workerGroup = new NioEventLoopGroup(16);
        try {
            //创建服务端的启动对象，设置参数
            ServerBootstrap bootstrap = new ServerBootstrap();
            //设置两个线程组boosGroup和workerGroup
            bootstrap.group(bossGroup, workerGroup)
                //设置服务端通道实现类型
                .channel(NioServerSocketChannel.class)
                //设置线程队列得到连接个数
                .option(ChannelOption.SO_BACKLOG, 128)
                //设置保持活动连接状态
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                //使用匿名内部类的形式初始化通道对象
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        //给pipeline管道设置处理器
                        socketChannel.pipeline().addLast(new EchoServerHandler());
                    }
                });//给workerGroup的EventLoop对应的管道设置处理器
            System.out.println("Netty Server 准备就绪...");
            //绑定端口号，启动服务端
            ChannelFuture channelFuture = bootstrap.bind(6666).sync();
            //对关闭通道进行监听
            channelFuture.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}