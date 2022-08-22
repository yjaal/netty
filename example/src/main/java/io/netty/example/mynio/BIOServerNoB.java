package io.netty.example.mynio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 阻塞IO服务端 --> 非阻塞模式
 **/
public class BIOServerNoB {

    private boolean stopFlag = false;

    private ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws InterruptedException {
        BIOServerNoB bioServer = new BIOServerNoB();
        bioServer.start(8888);
        Thread.sleep(1 * 60 * 1000);
        bioServer.setStopFlag(true);
    }

    public void start(int port) {
        ServerSocket sSocket = null;
        Socket cSocket = null;
        try {
            sSocket = new ServerSocket(port);
            // 通过设置超时时间来达到非阻塞
            sSocket.setSoTimeout(1000);
            System.out.println(nowTimeStr() + ": server socket started now");
            while (!stopFlag) {
                try {
                    cSocket = sSocket.accept();
                    System.out.println(
                        nowTimeStr() + ": id " + cSocket.hashCode() + "'s client socket "
                            + "connected");
                } catch (SocketTimeoutException e1) {
                    System.out.println("now time is: " + nowTimeStr());
                    continue;
                }
                pool.execute(new ClientSocketThread(cSocket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println(nowTimeStr() + ": server shutdown");
            try {
                sSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    class ClientSocketThread extends Thread {

        private Socket socket;

        private ClientSocketThread() {
        }

        public ClientSocketThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            BufferedReader bufferedReader = null;
            String msg = null;
            int count = 0;
            try {
                bufferedReader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
                while ((msg = bufferedReader.readLine()) != null) {
                    System.out.println("Msg which received is: " + socket.hashCode() + " " + msg);
                    count++;
                }
                System.out.println(nowTimeStr() + ": id is " + socket.hashCode() + "'s client "
                    + "socket over, total msg count is " + count);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (Objects.nonNull(bufferedReader)) {
                        bufferedReader.close();
                    }
                    if (Objects.nonNull(socket)) {
                        socket.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public String nowTimeStr() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public void setStopFlag(boolean stopFlag) {
        this.stopFlag = stopFlag;
    }
}


