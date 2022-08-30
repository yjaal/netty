package io.netty.example.mynio;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reactor-多线程
 *
 * @author YJ
 * @date 2022/1/7
 **/
public class MultiReactorNioServer {

    private final ExecutorService pool = Executors.newFixedThreadPool(10);

    public void start(int port) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        Selector selector = Selector.open();
        // 将ServerSocket注册到开关上面，表明接受外部连接
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("server started");
        for (; ; ) {
            try {
                if (selector.select() <= 0) {
                    continue;
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                break;
            }
            System.out.println("some key ready");
            Set<SelectionKey> readyKeys = selector.selectedKeys();
            Iterator<SelectionKey> iter = readyKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                try {
                    // 检查事件是否是一个新的已经就绪可以被接受的连接
                    if (key.isAcceptable()) {
                        // 一开始selector上面注册的是服务端的channel
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel client = server.accept();
                        // 将新的socket设置为非阻塞
                        client.configureBlocking(false);
                        // 这里将客户端channel注册到selector上
                        // 这里可以看到和阻塞IO的区别，就是以前阻塞等待变成了注册事件，
                        // 这样就可以一个socket监听多个socket连接
                        client.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ);

                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        buffer.put("connect succeed\r\n".getBytes(StandardCharsets.UTF_8));
                        buffer.flip();
                        client.write(buffer);
                    }

                    if (key.isReadable() || key.isWritable()) {
                        pool.submit(new Handler(selector, (SocketChannel) key.channel(), key));
                    }
                } catch (IOException ex) {
                    try {
                        key.channel().close();
                        key.cancel();
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        MultiReactorNioServer server = new MultiReactorNioServer();
        server.start(8888);
    }
}
