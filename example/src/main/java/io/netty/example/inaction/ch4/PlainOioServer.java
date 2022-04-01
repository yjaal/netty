package io.netty.example.inaction.ch4;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 普通阻塞网络编程实现
 *
 * @author YJ
 * @date 2022/1/7
 **/
public class PlainOioServer {

    public void serve(int port) throws IOException {
        final ServerSocket socket = new ServerSocket(port);
        try {
            for (; ; ) {
                // 阻塞，直到接收到一个客户端连接
                final Socket clientSocket = socket.accept();
                System.out.println("Accepted client connection from: " + clientSocket);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        OutputStream out;
                        try {
                            // 获取到客户端socket写出流
                            out = clientSocket.getOutputStream();
                            // 将相关信息写出到输出流中
                            out.write("Hi!\r\n".getBytes(Charset.forName("UTF-8")));
                            out.flush();
                            clientSocket.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                clientSocket.close();
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                    }
                }).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        Charset charset = Charset.forName("UTF-8");
        ByteBuf buf = Unpooled.copiedBuffer("Netty in Action rocks", charset);
        ByteBuf slicedBuf = buf.slice(0, 15);
        buf.setByte(0, (byte) 'J');
        System.out.println((char)buf.getByte(0));// J
        assert buf.getByte(0) == slicedBuf.getByte(0);
    }
}
