package io.netty.example.mynio;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * 处理类
 *
 * @author YJ
 * @date 2022/8/29
 **/
public class Handler implements Callable {

    private final Selector selector;

    private final SocketChannel socketChannel;

    private final SelectionKey key;

    ByteBuffer writeBuffer = ByteBuffer.allocate(32);
    ByteBuffer readBuffer = ByteBuffer.allocate(32);

    public Handler(Selector selector, SocketChannel socketChannel, SelectionKey key) {
        this.selector = selector;
        this.socketChannel = socketChannel;
        this.key = key;
    }

    @Override
    public Object call() throws Exception {
        if (!key.isReadable() && !key.isWritable()) {
            return null;
        }

        if (key.isReadable()) {
            // 获取该选择器上的“读就绪”状态的通道
            SocketChannel client = (SocketChannel) key.channel();
            readBuffer.clear();
            int len;
            StringBuilder content = new StringBuilder();
            while ((len = client.read(readBuffer)) > 0) {
                readBuffer.flip();
                content.append(new String(readBuffer.array(), 0, len, StandardCharsets.UTF_8));
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

        return null;
    }
}
