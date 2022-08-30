package io.netty.example.mynio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reactor-主从多线程-从reactor
 *
 * @author YJ
 * @date 2022/8/30
 **/
public class SlaveReactorNioServer {

    private Selector selector;

    private final ByteBuffer writeBuffer = ByteBuffer.allocate(32);
    private final ByteBuffer readBuffer = ByteBuffer.allocate(32);

    private static ExecutorService pool = Executors.newFixedThreadPool(
        2 * Runtime.getRuntime().availableProcessors());

    public void register(SocketChannel socketChannel) throws ClosedChannelException {
        socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    }

    public SlaveReactorNioServer() throws IOException {
        // 这里新开一个selector
        selector = Selector.open();
        this.select();
    }

    public void wakeup() {
        this.selector.wakeup();
    }

    public void select() {
        pool.submit(() -> {
            while (true) {
                if (selector.select(500) <= 0) {
                    continue;
                }
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (key.isReadable()) {
                        // 获取该选择器上的“读就绪”状态的通道
                        SocketChannel client = (SocketChannel) key.channel();
                        readBuffer.clear();
                        int len;
                        StringBuilder content = new StringBuilder();
                        while ((len = client.read(readBuffer)) > 0) {
                            readBuffer.flip();
                            content.append(
                                new String(readBuffer.array(), 0, len, StandardCharsets.UTF_8));
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
                }
            }
        });

    }
}
