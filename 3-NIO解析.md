# 1. NIO解析

Netty底层是基于NIO实现的，所以需要先对NIO进行详细分析，这对后面理解Netty对相关逻辑非常有帮助

参考

[知秋大佬相关文章](https://juejin.cn/post/6844903776818561032#heading-27)

[零拷贝技术的原理与在java中应用](https://blog.csdn.net/u022812849/article/details/109805403)

## 1.1 相关前置知识

### 1.1.1 零拷贝

`NIO` 中的零拷贝不是说不拷贝，而是说可以减少相关拷贝。这里涉及到两个类 `DirectByteBuffery`与 `MappedByteBuffer`。其中前者继承后者前者，这里涉及到内存映射和直接传输。

**先看普通的网络请求中相关数据传输逻辑**

![28](./img/28.png)

这里涉及两次用户态和内核态的转换，也就是两次系统调用，和四次上下文切换，一次系统调用涉及两次线程上下文切换。

四次线程上下文切换

![29](./img/29.png)

四次拷贝

> 1、磁盘文件读取到 `OS`内核缓冲区
>
> 2、内核缓冲区拷贝到应用（`jvm`堆）缓冲区
>
> 3、应用缓冲区到 `socket`缓冲区（内核中）
>
> 4、socket缓冲区拷贝到网卡进行发送

**内存映射**

内核缓冲区和应用缓冲区映射到同一块物理内存区，这样就减少了一次拷贝，直接从磁盘文件拷贝到了应用缓冲区。也就是常说的 `mmap`方式

于是传输流程就变成了如下方式

![30](./img/30.png)

**直接传输**

在 `Linux2.4`以后的版本又进行了改善，将 `socket buffer` 不再是一个缓冲区了，替换为了一个文件描述符，描述的是数据在内核缓冲区的数据从哪里开始，长度是多少，里面基本上不存储数据，大部分是指针，然后协议引擎 `protocol engine `（这里是 `NIC `）也是通过 `DMA `拷贝的方式从文件描述符读取。也就是说用户程序执行 `transferTo()`方法后，导致一次系统调用，从用户态切换到内核态。内在会通过 `DMA `将数据从磁盘中拷贝到 `Read buffer `。用一个文件描述符标记此次待传输数据的地址以及长度，`DMA `直接把数据从 `Read buffer `传输到 `NIC buffer `。数据拷贝过程都不用 `CPU`干预了。这里一共只有两次拷贝和两次上下文切换。

![31](./img/31.png)

以上可以通过相关方法可知

```java
// FileChannel#transferTo

// 实际执行 FileChannelImpl#transferTo
public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
    ...
    //当前文件大小
    long sz = size();
    if (position > sz)
        return 0;
    int icount = (int)Math.min(count, Integer.MAX_VALUE);
    //可传大小修正
    if ((sz - position) < icount)
        icount = (int)(sz - position);
    long n;
    // 若内核支持则使用直接传输
    if ((n = transferToDirectly(position, icount, target)) >= 0)
        return n;
    // 尝试内存映射文件传输
    if ((n = transferToTrustedChannel(position, icount, target)) >= 0)
        return n;
    // 慢速传输
    return transferToArbitraryChannel(position, icount, target);
}
```

这里可以看到，具体使用哪种方式取决于所在系统是否支持。同时第二种内存映射方式中涉及到内核缓冲区，我们写代码 `new byte[] `数组时，一般是都是“随意” 创建一个“任意大小”的数组。比如，`new byte[128]、new byte[1024]、...`

但是，对于硬盘块的读取而言，每次访问硬盘读数据时，并不是读任意大小的数据的，而是：每次读一个硬盘块或者若干个硬盘块(这是因为访问硬盘操作代价是很大的)。 因此，就需要有一个“中间缓冲区”--即内核缓冲区。先把数据从硬盘读到内核缓冲区中，然后再把数据从内核缓冲区搬到用户缓冲区。

在进行数据拷贝时，如果我们从内核缓冲区中直接命中了需要的数据，那么就直接返回了，不需要再从磁盘中拷贝了，否则没有命中，则需要从磁盘中请求 `page`，并同时读取紧随其后的几个 `page `（相当于一个预读取），所以这样我们说内核缓冲可以提升效率。但是在第一种方式中涉及拷贝次数太多，则效率相比起 `mmap`又显得低的原因。

同时还可以注意到，`mmap`这种方式由于涉及内核缓冲区，那么针对小文件的效率就很高了，但是其大小不会很大，如果文件很大的话，使用直接传输的方式效率更高。

最后 `DirectByteBuffery`与 `MappedByteBuffer`的区别就显而易见了，前者封装了直接传输操作，而后者封装的是 `mmap`，也就是只能进行文件 `IO `操作。`MappedByteBuffer `是根据 `mmap `产生的映射缓冲区，这部分缓冲区被映射到对应的文件页上，通过 `MappedByteBuffer`可以直接操作映射缓冲区，而这部分缓冲区又被映射到文件页上，操作系统通过对应内存页的调入和调出完成文件的写入和写出。

# 2. connect 与 bind 分析

## 2.1 基本阻塞IO分析

```java
package io.netty.example.mynio;
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


package io.netty.example.mynio;
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
```

### 2.1.1 new ServerSocket(port)

```java
// ServerSocket.java
public ServerSocket(int port) throws IOException {
    this(port, 50, null);
}
public ServerSocket(int port, int backlog, InetAddress bindAddr) throws IOException {
    setImpl();
    // ...
    try {
        bind(new InetSocketAddress(bindAddr, port), backlog);
    }
    // ...
}
```

这里主要关注两个步骤

* setImpl
  这里其实就是 `new SocksSocketImpl()`，也就是一个 `socket `实现
* bind
  这里其实就是将上面的 `impl `绑定到我们指定的端口上面，然后进行监听

### 2.1.2 accept

```java
// ServerSocket.java
public Socket accept() throws IOException {
	// ...
        Socket s = new Socket((SocketImpl) null);
        implAccept(s);
        return s;
}
protected final void implAccept(Socket s) throws IOException {
        SocketImpl si = null;
        try {
            if (s.impl == null)
              s.setImpl();
            else {
                s.impl.reset();
            }
            si = s.impl;
            s.impl = null;
            si.address = new InetAddress();
            si.fd = new FileDescriptor();
            getImpl().accept(si);
            // ...
        } catch (IOException e) {
            // ...
        }
        s.impl = si;
        s.postAccept();
}
```

这里首先可以看到 `new` 了一个新的 `Socket`，同时设置其 ` impl` 为空。而在 `implAccept`方法中，首先在 `s.setImpl` 方法中重新 `new `了一个新的 ` impl`，然后赋值给了 `si`，**同时还设置了文件描述符** 。这里其实就是通过客户端的连接建立了一个新的 `socket`，用于数据交互。

然后最重要的步骤 `getImpl().accept(si);`

```java
// 首先getImpl获得的是 ServerSocket最初的 impl
// si 为 Socket 重新实现的一个 impl，此 Socket 可以理解为客户端过来连接
// AbstractPlainSocketImpl.java
protected void accept(SocketImpl s) throws IOException {
        acquireFD();
        try {
            socketAccept(s);
        } finally {
            releaseFD();
        }
}
// PlainSocketImpl.java
// 这个类中的socketAccept方法在Unix系统上面执行的是native方法
// 而在windows上面的实现如下
// java.net.PlainSocketImpl#socketAccept --> DualStackPlainSocketImpl
@Override
void socketAccept(SocketImpl s) throws IOException {
    int nativefd = checkAndReturnNativeFD();
    // ...
    int newfd = -1;
    InetSocketAddress[] isaa = new InetSocketAddress[1];
    if (timeout <= 0) {  //<1>
        // 系统调用，阻塞模式
        newfd = accept0(nativefd, isaa); // <2>
    } else {
        // 设置为非阻塞模式
        configureBlocking(nativefd, false);
        try {
            // 等待 timeout 时长获取新的 socket
            waitForNewConnection(nativefd, timeout);
            // 非阻塞模式连接，立即返回
            newfd = accept0(nativefd, isaa);  // <3>
            if (newfd != -1) {
                // 无法获取任何数据，则恢复为阻塞模式
                configureBlocking(newfd, true);
            }
        } finally {
            configureBlocking(nativefd, true);
        }
    } // <4>
    /* Update (SocketImpl)s' fd */
    fdAccess.set(s.fd, newfd);
    /* Update socketImpls remote port, address and localport */
    InetSocketAddress isa = isaa[0];
    s.port = isa.getPort();
    s.address = isa.getAddress();
    s.localport = localport;
    if (preferIPv4Stack && !(s.address instanceof Inet4Address))
        throw new SocketException("Protocol family not supported");
}
//java.net.PlainSocketImpl#accept0
static native int accept0(int fd, InetSocketAddress[] isaa) throws IOException;
```

这里最开始我们获取了文件描述符，最后处理结束释放了文件描述符。而真正连接时，主要是创建了一个新的文件描述符 `newfd`， 然后替换掉了原先的文件描述符。结合上面的替换 `ServerSocket#impl`可以知道其实就是用客户端的 `Socket`通道替换掉了原先服务端 `new `出来的 `Socket`（其实一开始并未与任何其他机器连接，只是作用于服务端），此时客户端和服务端就真正建立了连接。

而且我们发现这里会根据 `timeout` 的值来进行阻塞和非阻塞的切换。

```java
// ServerSocket
public synchronized void setSoTimeout(int timeout) throws SocketException {
        if (isClosed())
            throw new SocketException("Socket is closed");
        getImpl().setOption(SocketOptions.SO_TIMEOUT, new Integer(timeout));
}
// AbstractPlainSocketImpl
public void setOption(int opt, Object val) throws SocketException {
        if (isClosedOrPending()) {
            throw new SocketException("Socket Closed");
        }
        boolean on = true;
        switch (opt) {
	// ...
        case SO_TIMEOUT:
            if (val == null || (!(val instanceof Integer)))
                throw new SocketException("Bad parameter for SO_TIMEOUT");
            int tmp = ((Integer) val).intValue();
            if (tmp < 0)
                throw new IllegalArgumentException("timeout < 0");
            timeout = tmp;
            break;
	// ...
        default:
            throw new SocketException("unrecognized TCP option: " + opt);
        }
        socketSetOption(opt, on, val);
}
```

这样我们可以通过上述方法来设置超时时间。

### 2.1.2 BIO 中实现非阻塞

这里首先要区分同步、异步和阻塞、非阻塞的区别。同步、异步是建立在系统层面的，同步表示系统收到请求，会在响应资源准备好之前一直等待；而异步表示系统会返回一个信号给服务端，用于告知后面会如何处理这个请求。阻塞、非阻塞是在应用层面，阻塞表示在响应资源准备好之前应用一直阻塞；而非阻塞表示应用还是在执行，但是会一直检查响应资源是否准备好。

在上面例子中阻塞发生在 `accept()` 和 `read() `方法处，我们可以将他们转换为非阻塞，但是这只是在应用层面，而不是在系统层面。

```java
package io.netty.example.mynio;
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
//    结果如下
//    2022-08-15 20:43:20: server socket started now
//    now time is: 2022-08-15 20:43:22
//    now time is: 2022-08-15 20:43:23
//    now time is: 2022-08-15 20:43:24
//    now time is: 2022-08-15 20:43:25
//    now time is: 2022-08-15 20:43:26
//    now time is: 2022-08-15 20:43:27
//    now time is: 2022-08-15 20:43:28
//    now time is: 2022-08-15 20:43:29
//    2022-08-15 20:43:29: id 559670971's client socket connected
//    now time is: 2022-08-15 20:43:30
//    now time is: 2022-08-15 20:43:31
//    now time is: 2022-08-15 20:43:32
//    now time is: 2022-08-15 20:43:33
//    now time is: 2022-08-15 20:43:34
//    Msg which received is: 559670971 2022-08-15 20:43:34: 第1条消息: 111
//    now time is: 2022-08-15 20:43:35
//    Msg which received is: 559670971 2022-08-15 20:43:36: 第2条消息: 222
//    now time is: 2022-08-15 20:43:36
```

这里可以看到，一直检查是否有客户端连接，只是在超时时间内进行阻塞。以上就是针对 `accept() `进行的非阻塞操作。

那对于 `read() `也是可以实现非阻塞的。首先看下如下方法

```java
// AbstractPlainSocketImpl#getInputStream
protected synchronized InputStream getInputStream() throws IOException {
        synchronized (fdLock) {
            if (isClosedOrPending())
                throw new IOException("Socket Closed");
            if (shut_rd)
                throw new IOException("Socket input is shutdown");
            if (socketInputStream == null)
                socketInputStream = new SocketInputStream(this);
        }
        return socketInputStream;
}

// SocketInputStream.java
public int read(byte b[]) throws IOException {
        return read(b, 0, b.length);
}
public int read(byte b[], int off, int length) throws IOException {
        return read(b, off, length, impl.getTimeout());
}
int read(byte b[], int off, int length, int timeout) throws IOException {
        int n;

        // EOF already encountered
        // ...

        boolean gotReset = false;

        // acquire file descriptor and do the read
        FileDescriptor fd = impl.acquireFD();
        try {
            n = socketRead(fd, b, off, length, timeout);
            if (n > 0) {
                return n;
            }
        } catch (ConnectionResetException rstExc) {
            gotReset = true;
        } finally {
            impl.releaseFD();
        }

        /*
         * We receive a "connection reset" but there may be bytes still
         * buffered on the socket
         */
        if (gotReset) {
            impl.setConnectionResetPending();
            impl.acquireFD();
            try {
                n = socketRead(fd, b, off, length, timeout);
                if (n > 0) {
                    return n;
                }
            } catch (ConnectionResetException rstExc) {
            } finally {
                impl.releaseFD();
            }
        }

        /*
         * If we get here we are at EOF, the socket has been closed,
         * or the connection has been reset.
         */
        if (impl.isClosedOrPending()) {
            throw new SocketException("Socket closed");
        }
        if (impl.isConnectionResetPending()) {
            impl.setConnectionReset();
        }
        if (impl.isConnectionReset()) {
            throw new SocketException("Connection reset");
        }
        eof = true;
        return -1;
}
private int socketRead(FileDescriptor fd,
                           byte b[], int off, int len,
                           int timeout)
        throws IOException {
        return socketRead0(fd, b, off, len, timeout);
}
```

这里同样可以看到，也是利用了超时时间，这样就可以实现 `read() `非阻塞。

```java
package io.netty.example.mynio;
/**
 * 阻塞IO服务端 --> 非阻塞模式
 **/
public class BIOServerNoBR {

    private boolean stopFlag = false;

    private ExecutorService socketPool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws InterruptedException {
        BIOServerNoBR bioServer = new BIOServerNoBR();
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
                socketPool.execute(new ClientSocketThread(cSocket));
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
                socket.setSoTimeout(1000);
            } catch (SocketException e) {
                e.printStackTrace();
            }

            try {
                bufferedReader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
                while (true) {
                    try {
                        while ((msg = bufferedReader.readLine()) != null) {
                            System.out.println(
                                "Msg which received is: " + socket.hashCode() + " " + msg);
                            count++;
                        }
                    } catch (IOException e) {
                        System.out.println(nowTimeStr() + ": not read data");
                        continue;
                    }
                    System.out.println(nowTimeStr() + ": id is" + socket.hashCode()
                        + "'s client socket read finish");
                    sleep(1000);
                }
            } catch (Exception e) {
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

//    2022-08-16 10:36:18: server socket started now
//    now time is: 2022-08-16 10:36:19
//    now time is: 2022-08-16 10:36:20
//    now time is: 2022-08-16 10:36:21
//    now time is: 2022-08-16 10:36:22
//    now time is: 2022-08-16 10:36:23
//    now time is: 2022-08-16 10:36:24
//    now time is: 2022-08-16 10:36:25
//    2022-08-16 10:36:25: id 559670971's client socket connected
//    now time is: 2022-08-16 10:36:26
//    2022-08-16 10:36:26: not read data
//    now time is: 2022-08-16 10:36:27
//    2022-08-16 10:36:27: not read data
//    now time is: 2022-08-16 10:36:28
```

注意，这里的超时时间是通过 `socket `设置的。这里我们可以将其看作最原始的 `NIO`。但是这种方式在面对大量的客户端时会存在问题，一个是线程之间的切换，另一个是虽然我们使用了线程池，但是可能会导致阻塞队列过大。此时 `NIO`可以帮助我们解决此问题，我们无须每次创建线程，`NIO`会为每个客户端新建一个 `Channel`。

## 2.2 NIO 分析

### 2.2.1 Channel 异步中断

其实 `Channel`就类似于 `Socket`的一个装饰器。

```java
package java.nio.channels;

public interface Channel extends Closeable {
    public boolean isOpen();

    public void close() throws IOException;
}

public interface InterruptibleChannel extends Channel {
    public void close() throws IOException;
}
```

这里 `Channel `接口中主要提供两个功能，一个是检查通道的状态是否正常，另外一个就是关闭。这里的close，表示线程调用次方法来关闭 `socket`。当一个线程调用后，另外一个线程调用将阻塞直到第一个线程执行完毕。如果 `Channel`关闭了，那在其上的 `IO`操作将返回 `ClosedChannelException`。

而 `InterruptibleChannel`继承了 `Channel`，同样也有一个 `close`方法，这里和 `Channel`中也是一个增强，`Channel`中表示关闭 `socket`，而 `InterruptibleChannel`表示线程中断（也就是不是通过调用 `close`方法来关闭，而是通过中断 `Channel`所在线程实现关闭）。同样如果 `Channel`关闭了，那在其上的 `IO`操作将返回 `AsynchronousClosedException`。

> 传统 `IO`是不支持中断的，所以如果代码在 `read/write`等操作阻塞的话，是无法被中断的。这就无法和 `Thead`的 `interrupt`模型配合使用了。`NIO`众多的升级点中就包含了 `IO`操作对中断的支持。`InterruptiableChannel`表示支持中断的 `Channel`。我们常用的 `FileChannel，SocketChannel，DatagramChannel`都实现了这个接口。

这里最上层的一个实现是AbstractInterruptibleChannel，在其文档中说明了如何正确的编码

```java
boolean completed = false;
try {
       begin();
       completed = ...;    // Perform blocking I/O operation
       return ...;         // Return result
} finally {
    end(completed);
}
```

`NIO`规定了，在阻塞 `IO`的语句前后，需要调用 `begin()`和 `end()`方法; 另外要求是Channel需要实现 `AbstractInterruptibleChannel#implCloseChannel `这个方法。`AbstractInterruptibleChannel`在处理中断时，会调用这个方法，使用 `Channel`的具体实现来关闭。

参考：[InterruptibleChannel 与可中断 IO](https://github.com/muyinchen/woker/blob/master/NIO/%E8%A1%A5%E5%85%85%E6%BA%90%E7%A0%81%E8%A7%A3%E8%AF%BB%EF%BC%9AInterruptibleChannel%20%E4%B8%8E%E5%8F%AF%E4%B8%AD%E6%96%AD%20IO.md)

**begin()方法**

```java
 // 保存中断处理对象实例
 private Interruptible interruptor;
 // 保存被中断线程实例
 private volatile Thread interrupted;

/**
  * Marks the beginning of an I/O operation that might block indefinitely.
  */
protected final void begin() {
    if (interruptor == null) {
	// 这里就是创建i个中断处理对象
        interruptor = new Interruptible() {
            public void interrupt(Thread target) {
                synchronized (closeLock) {
		    // 如果Channel已关闭，那么直接返回，jdk11中已经使用closed来判断
                    if (!open)
                        return;
                    open = false;
                    interrupted = target;
                    try {
			// 调用具体实现来关闭Channel
                        AbstractInterruptibleChannel.this.implCloseChannel();
                    } catch (IOException x) { }
                }
            }};
    }
    // 这里就是登记中断处理对象到当前线程
    blockedOn(interruptor);
    Thread me = Thread.currentThread();
    // 这里其实就是说如果当前线程已经中断，可能中断处理对象没有调用，这里手动再调用一次
    if (me.isInterrupted())
        interruptor.interrupt(me);
}
```

这里最主要的步骤就是将中断处理对象登记到当前线程，当当前线程被中断时就会被执行。

```java
static void blockedOn(Interruptible intr) {         // package-private
        sun.misc.SharedSecrets.getJavaLangAccess()
	.blockedOn(Thread.currentThread(), intr);
}
```

要注意，`JavaLangAccess`对象就是提供java.lang包下一些非公开的方法的访问，在 `System`初始化时创建

```java
 // java.lang.System#setJavaLangAccess

 private static void setJavaLangAccess() {
    sun.misc.SharedSecrets.setJavaLangAccess(new sun.misc.JavaLangAccess(){
         public void blockedOn(Thread t, Interruptible b) {
             t.blockedOn(b);
        }

        //...
     });
 }
```

可以看出，`sun.misc.JavaLangAccess#blockedOn`保证的就是 `java.lang.Thread#blockedOn`这个包级别私有的方法：

```java
private volatile Interruptible blocker;
private final Object blockerLock = new Object();

 /* Set the blocker field; invoked via sun.misc.SharedSecrets from java.nio code
  */
 void blockedOn(Interruptible b) {
     // 串行化blocker相关操作
     synchronized (blockerLock) {
         blocker = b;
     }
 }
```

这里也比较简单，主要是要看 `Thread`被中断时是如何调用中断处理器的

```java
public void interrupt() {
        if (this != Thread.currentThread())
            checkAccess();

        synchronized (blockerLock) {
            Interruptible b = blocker;
            if (b != null) {
                interrupt0();           // Just to set the interrupt flag
                b.interrupt(this);
                return;
            }
        }
        interrupt0();
}
```

**end()方法**

`begin()`方法负责添加 `Channel`的中断处理器到当前线程。`end()`是在 `IO`操作执行完/中断完后的操作，负责判断中断是否发生，如果发生判断是当前线程发生还是别的线程中断把当前操作的 `Channel`给关闭了，对于不同的情况，抛出不同的异常。

```java
 protected final void end(boolean completed) throws AsynchronousCloseException {
     // 清空线程的中断处理器引用，避免线程一直存活导致中断处理器无法被回收
     blockedOn(null);
     Thread interrupted = this.interrupted;
     if (interrupted != null && interrupted == Thread.currentThread()) {
         interrupted = null;
        throw new ClosedByInterruptException();
    }
     // 如果这次没有读取到数据，并且Channel被另外一个线程关闭了，则排除Channel被异步关闭的异常
     if (!completed && !open)
         throw new AsynchronousCloseException();
 }
```

**场景分析**

并发的场景分析起来就是复杂，上面的代码不多，但是场景很多，我们以 `sun.nio.ch.FileChannelImpl#read(java.nio.ByteBuffer)`为例分析一下可能的场景：

1. `A`线程 `read `，`B`线程中断 `A`线程：A线程抛出 `ClosedByInterruptException`异常
2. `A，B`线程 `read`，`C`线程中断  `A`线程

* `A`被中断时，B刚刚进入 `read` 方法：A线程抛出 `ClosedByInterruptException `异常，B线程 `ensureOpen `方法抛出 `ClosedChannelException`异常
* A被中断时，B阻塞在底层 `read`方法中：A线程抛出 `ClosedByInterruptException`异常，B线程底层方法抛出异常返回，`end`方法中抛出 `AsynchronousCloseException`异常
* A被中断时，B已经读取到数据：A线程抛出 `ClosedByInterruptException`异常，B线程正常返回

### 2.2.2 赋予Channel可被多路复用的能力

`Channel`是需要被 `Selector`来管理的，`Selector`根据 `Channel`的状态来分配任务，所以 `Channel`应该被注册到 `Selector`上面，同时会返回一个 `SelectionKey`对象来表示这个 `Channel`在 `Selector`上的状态。

```java
public final SelectionKey register(Selector sel, int ops,
        Object att) throws ClosedChannelException {
    synchronized (regLock) {
        // 未打开
        if (!isOpen())
            throw new ClosedChannelException();
        // 非法状态标识
        if ((ops & ~validOps()) != 0)
            throw new IllegalArgumentException();
        // 阻塞状态
        if (blocking)
            throw new IllegalBlockingModeException();
        SelectionKey k = findKey(sel);
        if (k != null) {
            // 重新给状态赋值
            k.interestOps(ops);
            // 这里就是赋予一个对象，正常情况下一般传入的是null
            // 暂且不管
            k.attach(att);
        }
        if (k == null) {
            // 新的注册
            synchronized (keyLock) {
                if (!isOpen())
                    throw new ClosedChannelException();
                k = ((AbstractSelector)sel).register(this, ops, att);
                addKey(k);
            }
        }
        return k;
    }
}
private void addKey(SelectionKey k) {
    assert Thread.holdsLock(keyLock);
    int i = 0;
    if ((keys != null) && (keyCount < keys.length)) {
        // keys中如果有空元素，则在后面直接赋值
        for (i = 0; i < keys.length; i++)
            if (keys[i] == null)
                break;
    } else if (keys == null) {
        keys =  new SelectionKey[3];
    } else {
        // keys数组长度不够了，扩容
        int n = keys.length * 2;
        SelectionKey[] ks =  new SelectionKey[n];
        for (i = 0; i < keys.length; i++)
            ks[i] = keys[i];
        keys = ks;
        i = keyCount;
    }
    // 赋值
    keys[i] = k;
    keyCount++;
}
```

上述注册逻辑较为简单。一旦 `Channel`注册到 `Selector`上，将一直保持注册状态直到被解除注册。在解除注册的时候会解除 `Selector`分配给 `Channel`的所有资源。但是 `Channel`并没有提供解除注册的方法，这里需要通过 `SelectionKey`来完成，因为 `SelectionKey `代表 `Channnel `注册，我们可以通过解除 `SelectionKey `来完成对 `Channel`注册的解除

```java
// AbstractSelectionKey
public final void cancel() {
    synchronized (this) {
        if (valid) {
            valid = false;
            ((AbstractSelector)selector()).cancel(this);
        }
    }
}
// AbstractSelector
void cancel(SelectionKey k) {
    synchronized (cancelledKeys) {
        cancelledKeys.add(k);
    }
}
```

可以看到这里并没有真正解除，而是将SelectionKey添加到了一个缓存数组中。

```java
//在下一次select操作的时候来解除那些要求cancel的key，即解除Channel注册
//sun.nio.ch.SelectorImpl#select(long)
@Override
public final int select(long timeout) throws IOException {
    if (timeout < 0)
        throw new IllegalArgumentException("Negative timeout");
    return lockAndDoSelect(null, (timeout == 0) ? -1 : timeout);
}
//sun.nio.ch.SelectorImpl#lockAndDoSelect
private int lockAndDoSelect(Consumer<SelectionKey> action, long timeout)
    throws IOException {
    synchronized (this) {
        ensureOpen();
        if (inSelect)
            throw new IllegalStateException("select in progress");
        inSelect = true;
        try {
            synchronized (publicSelectedKeys) {
                return doSelect(action, timeout);
            }
        } finally {
            inSelect = false;
        }
    }
}
//sun.nio.ch.WindowsSelectorImpl#doSelect
protected int doSelect(Consumer<SelectionKey> action, long timeout)
    throws IOException {
    assert Thread.holdsLock(this);
    this.timeout = timeout;
    processUpdateQueue();
    //重点关注此方法
    processDeregisterQueue();
    if (interruptTriggered) {
        resetWakeupSocket();
        return 0;
    }
    ...
}
// sun.nio.ch.SelectorImpl#processDeregisterQueue
protected final void processDeregisterQueue() throws IOException {
    assert Thread.holdsLock(this);
    assert Thread.holdsLock(publicSelectedKeys);
    Set<SelectionKey> cks = cancelledKeys();
    synchronized (cks) {
        if (!cks.isEmpty()) {
            Iterator<SelectionKey> i = cks.iterator();
            while (i.hasNext()) {
                SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
                i.remove();
                // remove the key from the selector
                implDereg(ski);
                selectedKeys.remove(ski);
                keys.remove(ski);
                // remove from channel's key set
                deregister(ski);
                SelectableChannel ch = ski.channel();
                if (!ch.isOpen() && !ch.isRegistered())
                    ((SelChImpl)ch).kill();
            }
        }
    }
}
```

当 `Channel`关闭时，无论通过调用 `close`方法还是通过线程中断的方式，内部都会取消关于这个 `Channel`的所有 `key`，内部调用 `cancel`方法。同时 `Channel`是支持多个 `Ops`的，在 `Selector`上面是无法重复注册的，只能进行 `Ops`的改变。所以当继承了 `SelectableChannel`之后，这个 `Channel`就可以安全的由多个并发线程来使用了。

这里，要注意的是，继承了 `AbstractSelectableChannel`这个类之后，新创建的channel始终处于阻塞模式。然而与 `Selector`的多路复用有关的操作必须基于非阻塞模式，所以在注册到 `Selector`之前，必须将 `channel`置于非阻塞模式，并且在取消注册之前，`channel`可能不会返回到阻塞模式。以通过调用 `channel`的 `isBlocking`方法来确定其是否为阻塞模式。如果不修改为非阻塞模式，那么会报 `IllegalBlockingModeException`异常。

### 2.2.3 赋予Channel Socket能力

```java
public interface NetworkChannel extends Channel {
    // 绑定到某个地址
    NetworkChannel bind(SocketAddress local) throws IOException;
    // 获取到绑定地址
    SocketAddress getLocalAddress() throws IOException;
    // 设置状态配置
    <T> NetworkChannel setOption(SocketOption<T> name, T value) throws IOException;

    <T> T getOption(SocketOption<T> name) throws IOException;

    Set<SocketOption<?>> supportedOptions();
}
```

下面我们看下绑定方法

```java
//sun.nio.ch.ServerSocketChannelImpl#bind
@Override
public ServerSocketChannel bind(SocketAddress local, int backlog) throws IOException {
    synchronized (stateLock) {
        ensureOpen();
        //Using localAddress to check whether bind has been used
        if (localAddress != null)
            throw new AlreadyBoundException();
        //InetSocketAddress(0) means all addresses which have been bind to local, system will choose suitable sockets
        InetSocketAddress isa = (local == null)
                                ? new InetSocketAddress(0)
                                : Net.checkAddress(local);
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
            sm.checkListen(isa.getPort());
        NetHooks.beforeTcpBind(fd, isa.getAddress(), isa.getPort());
        Net.bind(fd, isa.getAddress(), isa.getPort());
        //Listener started, if backlog in s is smaller than 1. it will default accept 50 connections.
        Net.listen(fd, backlog < 1 ? 50 : backlog);
        localAddress = Net.localAddress(fd);
    }
    return this;
}
```

这里倒无须深究，主要就是绑定，然后监听。最终都是调用系统层级的方法来实现的。对于服务端我们需要调用 `bind`方法来实现端口绑定，而客户端则会默认调用。而监听就是监听服务端的某个端口上是否有连接过来，如果 `backlog`小于1，那么可以接受 `50`个连接。

`accept`方法基本逻辑和之前一样

```java
//sun.nio.ch.ServerSocketChannelImpl#accept()
@Override
public SocketChannel accept() throws IOException {
    acceptLock.lock();
    try {
        int n = 0;
        FileDescriptor newfd = new FileDescriptor();
        InetSocketAddress[] isaa = new InetSocketAddress[1];

        boolean blocking = isBlocking();
        try {
            begin(blocking);
            do {
		// 阻塞等待连接(当未被中断且未被关闭)
                n = accept(this.fd, newfd, isaa);
            } while (n == IOStatus.INTERRUPTED && isOpen());
        } finally {
            end(blocking, n > 0);
            assert IOStatus.check(n);
        }

        if (n < 1)
            return null;
        // 新接收的socket初始设置为阻塞模式
        IOUtil.configureBlocking(newfd, true);

        InetSocketAddress isa = isaa[0];
        // 用新接收的socket创建SocketChannel
        SocketChannel sc = new SocketChannelImpl(provider(), newfd, isa);

        // check permitted to accept connections from the remote address
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            try {
                //check address and port access
                sm.checkAccept(isa.getAddress().getHostAddress(), isa.getPort());
            } catch (SecurityException x) {
                sc.close();
                throw x;
            }
        }
         //return socketchannelimpl  
        return sc;

    } finally {
        acceptLock.unlock();
    }
}
```

基本的模式和之前一样，`begin`开始，`end`结束。后面的 `end(blocking, n > 0)` 的第二个参数 `completed`只是判断这个阻塞等待过程是否结束，而不是说 `Channel`关闭了。接收成功了则返回1，否则返回 `UNAVAILABLE, INTERRUPTED`。

在之前 `BIO`解析处说到当建立连接时会绑定一个新的 `SocketImpl`，然后通信时使用这个新的类对象，这里回顾一下

```java
//java.net.ServerSocket#createImpl
void createImpl() throws SocketException {
    if (impl == null)
        setImpl();
    try {
        impl.create(true);
        created = true;
    } catch (IOException e) {
        throw new SocketException(e.getMessage());
    }
}
//java.net.AbstractPlainSocketImpl#create
protected synchronized void create(boolean stream) throws IOException {
    this.stream = stream;
    if (!stream) {
        ResourceManager.beforeUdpCreate();
        // only create the fd after we know we will be able to create the socket
        fd = new FileDescriptor();
        try {
            socketCreate(false);
            SocketCleanable.register(fd);
        } catch (IOException ioe) {
            ResourceManager.afterUdpClose();
            fd = null;
            throw ioe;
        }
    } else {
        fd = new FileDescriptor();
	// 这里创建新的SocketImpl
        socketCreate(true);
        SocketCleanable.register(fd);
    }
    if (socket != null)
        socket.setCreated();
    if (serverSocket != null)
        serverSocket.setCreated();
}
@Override
void socketCreate(boolean stream) throws IOException {
    if (fd == null)
        throw new SocketException("Socket closed");

    int newfd = socket0(stream);

    fdAccess.set(fd, newfd);
}
```

这里可以看到使用系统方法 `socket0`创建了一个新的连接，然后返回了一个新的文件描述符，然后使用新的文件描述符 `newfd`替换了原有的 `fd`，这样就和新的 `Socket`建立了绑定关系。

而在 `ServerSocketChannel`中我们使用 `accept`获取客户端连接，从代码中可以清晰的看到

`new SocketChannelImpl(provider(), newfd, isa)`创建了一个新的对象。
