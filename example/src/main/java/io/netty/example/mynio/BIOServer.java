package io.netty.example.mynio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 阻塞IO服务端
 **/
public class BIOServer {

    private boolean stopFlag = false;

    public static void main(String[] args) throws InterruptedException {
        BIOServer bioServer = new BIOServer();
        bioServer.start(8888);
        Thread.sleep(1 * 60 * 1000);
        bioServer.setStopFlag(true);
    }

    public void start(int port) {
        ServerSocket sSocket = null;
        BufferedReader bufferedReader = null;
        Socket cSocket = null;
        String msg = null;
        int count = 0;
        try {
            sSocket = new ServerSocket(port);
            System.out.println(nowTimeStr() + ": server socket started now");
            cSocket = sSocket.accept();
            System.out.println(nowTimeStr() + ": id " + cSocket.hashCode() + "'s client socket "
                + "connected");
            bufferedReader = new BufferedReader(
                new InputStreamReader(cSocket.getInputStream()));
            while (true) {
                while ((msg = bufferedReader.readLine()) != null) {
                    System.out.println("Msg which received is: " + cSocket.hashCode() + " " + msg);
                    count++;
                }
                System.out.println(nowTimeStr() + ": id is " + cSocket.hashCode() + "'s client "
                    + "socket over, total msg count is " + count);
                if (!stopFlag) {
                    System.out.println(nowTimeStr() + ": server shutdown");
                    return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (Objects.nonNull(bufferedReader)) {
                    bufferedReader.close();
                }
                if (Objects.nonNull(cSocket)) {
                    cSocket.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
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
