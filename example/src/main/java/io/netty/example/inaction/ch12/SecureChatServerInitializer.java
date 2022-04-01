package io.netty.example.inaction.ch12;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import javax.net.ssl.SSLEngine;

/**
 * 为ChannelPipeline加密, 扩展 ChatServerInitializer 以添加加密
 *
 * @author YJ
 * @date 2022/4/1
 **/
public class SecureChatServerInitializer extends ChatServerInitializer {

    private final SslContext context;

    public SecureChatServerInitializer(ChannelGroup group, SslContext context) {
        super(group);
        this.context = context;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        super.initChannel(ch);
        SSLEngine engine = context.newEngine(ch.alloc());
        engine.setUseClientMode(false);
        // 添加到第一个位置
        ch.pipeline().addFirst(new SslHandler(engine));
    }
}
