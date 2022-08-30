package io.netty.example.mynio;


import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Reactor-主从多线程-主reactor
 *
 * @author YJ
 * @date 2022/1/7
 **/
public class MasterReactorNioServer {

    private List<SlaveReactorNioServer> slaves = new ArrayList<>(
        Runtime.getRuntime().availableProcessors()
    );

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
                if (selector.select() < 0) {
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
                SlaveReactorNioServer slave = new SlaveReactorNioServer();
                try {
                    // 检查事件是否是一个新的已经就绪可以被接受的连接
                    if (key.isAcceptable()) {
                        // 一开始selector上面注册的是服务端的channel
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel client = server.accept();
                        // 将新的socket设置为非阻塞
                        client.configureBlocking(false);

                        slave.register(client);
                        slaves.add(slave);
                    }
                } catch (IOException ex) {
                    try {
                        slaves.remove(slave);
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
        MasterReactorNioServer server = new MasterReactorNioServer();
        server.start(8888);
    }
}
