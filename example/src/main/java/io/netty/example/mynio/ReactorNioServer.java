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

/**
 * Reactor-单线程
 *
 * @author YJ
 * @date 2022/1/7
 **/
public class ReactorNioServer {

    public void start(int port) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        Selector selector = Selector.open();
        // 将ServerSocket注册到开关上面，表明接受外部连接
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        ByteBuffer writeBuffer = ByteBuffer.allocate(32);
        ByteBuffer readBuffer = ByteBuffer.allocate(32);
        System.out.println("server started");
        for (; ; ) {
            try {
                // 这里是选择ready的key
                selector.select();
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

                    if (key.isReadable()) {
                        // 获取该选择器上的“读就绪”状态的通道
                        SocketChannel client = (SocketChannel) key.channel();
                        readBuffer.clear();
                        int len;
                        StringBuilder content = new StringBuilder();
                        while ((len = client.read(readBuffer)) > 0) {
                            readBuffer.flip();
                            content.append(new String(readBuffer.array(), 0, len,
                                StandardCharsets.UTF_8));
                            readBuffer.clear();
                        }
                        // 当读不到数据时len=0，当客户端关闭时len=-1
                        if (len < 0) {
                            System.out.println("client closed");
                            client.close();
                            key.cancel();
                        } else {
                            System.out.println("accepted data: " + content);
                        }
                        key.interestOps(SelectionKey.OP_WRITE);
                    }

                    if (key.isWritable()) {
                        SocketChannel client = (SocketChannel) key.channel();
                        writeBuffer.clear();
                        // 这里简单做了
                        // 在读取完client数据之后暂时都统一写入一个字符串
                        writeBuffer.put("read success\r\n".getBytes(StandardCharsets.UTF_8));
                        writeBuffer.flip();
                        client.write(writeBuffer);
                        key.interestOps(SelectionKey.OP_READ);
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
        ReactorNioServer server = new ReactorNioServer();
        server.start(8888);
    }
}
