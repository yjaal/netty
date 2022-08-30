package io.netty.example.mynio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * NIO客户端
 *
 * @author YJ
 * @date 2022/8/26
 **/
public class NIOClient {


    public void start(String ip, int port) {
        SocketChannel client = null;
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        try {
            client = SocketChannel.open();
            client.connect(new InetSocketAddress(ip, port));
            Scanner reader = new Scanner(System.in);
            for (; ; ) {
                // 简单做，就读取一行
                String inputLine = reader.nextLine();
                if (inputLine.equalsIgnoreCase("exit")) {
                    break;
                }
                buffer.clear();
                buffer.put(inputLine.getBytes(StandardCharsets.UTF_8));
                buffer.flip();
                client.write(buffer);
                buffer.clear();

                int len = client.read(buffer);
                if (len == -1) {
                    break;
                }
                buffer.flip();
                byte[] datas = new byte[buffer.remaining()];
                buffer.get(datas);
                System.out.println("from server data: " +
                    new String(datas, StandardCharsets.UTF_8));
                buffer.clear();
            }

        } catch (Exception e) {
            // ignore
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    public static void main(String[] args) {
        NIOClient client = new NIOClient();
        client.start("127.0.0.1", 8888);
    }
}
