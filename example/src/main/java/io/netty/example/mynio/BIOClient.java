package io.netty.example.mynio;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 阻塞IO客户端
 **/
public class BIOClient {

    public static void main(String[] args) {
        BIOClient bioClient = new BIOClient();
        bioClient.start("127.0.0.1", 8888);
    }

    public void start(String host, int port) {
        Socket socket = null;
        BufferedReader bufferedReader = null;
        BufferedWriter bufferedWriter = null;
        int count = 0;
        String msg = null;
        try {
            socket = new Socket(host, port);
            System.out.println(nowTimeStr() + ": client socket started now");
            bufferedReader = new BufferedReader(new InputStreamReader(System.in));
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            while ((msg = bufferedReader.readLine()) != null) {
                msg = nowTimeStr() + ": 第" + (count + 1) + "条消息: " + msg + "\n";
                bufferedWriter.write(msg);
                bufferedWriter.flush();
                count++;
                if (count > 3) {
                    System.out.println(nowTimeStr() + ": client shutdown");
                    return;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (Objects.nonNull(bufferedReader)) {
                    bufferedReader.close();
                }
                if (Objects.nonNull(bufferedWriter)) {
                    bufferedWriter.close();
                }
                if (Objects.nonNull(socket)) {
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public String nowTimeStr() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
