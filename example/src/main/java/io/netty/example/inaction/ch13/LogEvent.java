package io.netty.example.inaction.ch13;

import java.net.InetSocketAddress;

/**
 * 数据对象
 *
 * @author YJ
 * @date 2022/4/1
 **/
public class LogEvent {

    public static final byte SEPARATOR = (byte) ':';
    // 发送LogEvent到源地址
    private final InetSocketAddress source;
    // 日志文件名称
    private final String logfile;
    // 消息内容
    private final String msg;
    // 接收LogEvent事件
    private final long received;

    public LogEvent(String logfile, String msg) {
        this(null, -1, logfile, msg);
    }

    public LogEvent(InetSocketAddress source, long received, String logfile, String msg) {
        this.source = source;
        this.logfile = logfile;
        this.msg = msg;
        this.received = received;
    }

    public long getReceivedTimestamp() {
        return received;
    }

    public InetSocketAddress getSource() {
        return source;
    }

    public String getLogfile() {
        return logfile;
    }

    public String getMsg() {
        return msg;
    }

    public long getReceived() {
        return received;
    }
}
