package io.netty.example.inaction.ch12;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedNioFile;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * 处理HTTP请求, 扩展 SimpleChannelInboundHandler 以处理 FullHttpRequest 消息
 *
 * @author YJ
 * @date 2022/3/31
 **/
public class HTTPRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final String wsUri;
    private static final File INDEX;

    static {
        URL location = HTTPRequestHandler.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            String path = location.toURI() + "index.html";
            System.out.println("path: " + path);
            path = !path.contains("file:") ? path : path.substring(5);
            INDEX = new File(path);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unable to locate index.html", e);
        }
    }

    public HTTPRequestHandler(String wsUri) {
        this.wsUri = wsUri;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (wsUri.equalsIgnoreCase(req.uri())) {
            // 如果请求了 WebSocket 协议升级，则增加引用
            // 计数(调用 retain()方法)， 并将它传递给下一个 ChannelInboundHandler
            // 之所以需要调用retain()方法，是因为调用channelRead() 方法完成之后，
            // 它将调用 FullHttpRequest 对象上的 release()方法以释放它的资源。
            ctx.fireChannelRead(req.retain());
        } else {
            // HTTP/1.1 协议里设计 100 (Continue) HTTP 状态码的的目的是，在客户端发送 Request Message 之前，
            // HTTP/1.1 协议允许客户端先判定服务器是否愿意接受客户端发来的消息主体（基于 Request Headers）。
            // 即，客户端 在 Post（较大）数据到服务端之前，允许双方“握手”，如果匹配上了，Client
            // 才开始发送（较大）数据。这么做的原因是，如果客户端直接发送请求数据，但是服务器又将该请求拒绝的话，
            // 这种行为将带来很大的资源开销。协议对 HTTP客户端的要求是：
            // 如果 client 预期等待“100-continue”的应答，那么它发的请求必须包含一个
            // " Expect: 100-continue"  的头域！
            if (HttpUtil.is100ContinueExpected(req)) {
                // 处理 100 Continue 请求以符合 HTTP 1.1 规范
                send100Continue(ctx);
            }
            // 读取 index.html
            RandomAccessFile file = new RandomAccessFile(INDEX, "r");
            DefaultHttpResponse resp = new DefaultHttpResponse(
                req.protocolVersion(), HttpResponseStatus.OK);
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            boolean keepAlive = HttpUtil.isKeepAlive(req);
            if (keepAlive) {
                // 如果请求了 keep-alive， 则添加所需要的 HTTP 头信息
                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, file.length());
                resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            // 将 HttpResponse 写到客户端
            // 这不是一个 FullHttpResponse，因为它只是响应的第一个部分。
            // 此外，这里也不会调用 writeAndFlush()方法， 在结束的时候才会调用。
            ctx.write(resp);
            if (ctx.pipeline().get(SslHandler.class) == null) {
                //  将 index.html 写到客户端
                // 如果不需要加密和压缩，那么可以通过将
                // index.html 的内容存储到 DefaultFileRegion 中来达到最佳效率。
                ctx.write(new DefaultFileRegion(file.getChannel(), 0, file.length()));
            } else {
                ctx.write(new ChunkedNioFile(file.getChannel()));
            }
            // 写 LastHttpContent 并冲刷至客户端
            ChannelFuture future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private static void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE
        );
        ctx.writeAndFlush(resp);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}
