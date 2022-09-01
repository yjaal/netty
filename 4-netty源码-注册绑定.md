# 1. 前言

还是使用之前的一个小例子进行说明

```java
package io.netty.example.inaction.ch2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 服务端
 **/
public class EchoServer {

    public static void main(String[] args) throws Exception {
        // 创建两个线程组 boosGroup、workerGroup
        // boosGroup用于监听客户端连接，专门负责与客户端的连接，并把连接注册到
        // workGroup的Selector中，设置线程数为1
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // 用于处理每一个连接发生的读写事件
        EventLoopGroup workerGroup = new NioEventLoopGroup(16);
        try {
            //创建服务端的启动对象，设置参数
            ServerBootstrap bootstrap = new ServerBootstrap();
            //设置两个线程组boosGroup和workerGroup
            bootstrap.group(bossGroup, workerGroup)
                //设置服务端通道实现类型
                .channel(NioServerSocketChannel.class)
                //设置线程队列得到连接个数
                .option(ChannelOption.SO_BACKLOG, 128)
                //设置保持活动连接状态
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                //使用匿名内部类的形式初始化通道对象
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        //给pipeline管道设置处理器
                        socketChannel.pipeline().addLast(new EchoServerHandler());
                    }
                });//给workerGroup的EventLoop对应的管道设置处理器
            System.out.println("Netty Server 准备就绪...");
            //绑定端口号，启动服务端
            ChannelFuture channelFuture = bootstrap.bind(6666).sync();
            //对关闭通道进行监听
            channelFuture.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}

package io.netty.example.inaction.ch2;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * 客户端
 **/
public class EchoClient {

    public static void main(String[] args) throws InterruptedException {
        NioEventLoopGroup eventExecutors = new NioEventLoopGroup();
        try {
            //创建bootstrap对象，配置参数
            Bootstrap bootstrap = new Bootstrap();
            //设置线程组
            bootstrap.group(eventExecutors)
                //设置客户端的通道实现类型
                .channel(NioSocketChannel.class)
                //使用匿名内部类初始化通道
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        //添加客户端通道的处理器
                        ch.pipeline().addLast(new EchoClientHandler());
                    }
                });
            System.out.println("Netty Client 准备就绪，随时可以起飞~");
            //连接服务端
            ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", 6666).sync();
            //对通道关闭进行监听
            channelFuture.channel().closeFuture().sync();
        } finally {
            //关闭线程组
            eventExecutors.shutdownGracefully();
        }
    }
}

```

从这里可以看到主要是做了几个操作，一开始进行了相关配置，然后进行了绑定，最后就是等待监听器的结果，然后将结果交给相关的处理类进行处理。`Netty` 是基于 `NIO`的，那么我们在分析的时候就需要结合 `NIO `的做法来将 `Netty`  的相关操作和 `NIO` 结合起来，这样就会更清晰。那这里肯定是在绑定方法中做了一系列初始化、注册、绑定的操作。那可以以此为入口进行分析。

# 2. 创建 NioEventLoopGroup

先看下 `NioEventLoopGroup`基本的继承关系

```properties
              NioEventLoopGroup[C]
                     |
             MultithreadEventLoopGroup[C]
                 |                    |
MultithreadEventExecutorGroup[C]    EventLoopGroup[I]
                 |                        |
  AbstractEventExecutorGroup[C]           |
                          |               |
                          EventExecutorGroup[I]
                             |            |
                         Iterable[I]  ScheduledExecutorService[I]                                          |
                                      ExecutorService[I]
                                          |
                                        Executor[I]

```

通过上面继承关系可以发现，主要就是 `JDK`中线程池和迭代器的功能。而在方法 `next()`中返回的是 `NioEventLoop`，这就很容易理解了，`NioEventLoopGroup`是一个包含多个``NioEventLoop的组，而 `NioEventLoop`可以理解为一个线程循环，这里通过 `NioEventLoop`继承 `SingleThreadEventLoop`也可以明白这点。我们可以通过调试也可以发现 `NioEventLoopGroup`中有一个 `chidren`的属性，这就是一个 `NioEventLoop`数组。

下面看一下 `NioEventLoop`中相关内容

```java
// 优化后或者叫包装后的selector
private Selector selector;
// 原始NIO的selector
private Selector unwrappedSelector;
// 已被选择的key缓存
private SelectedSelectionKeySet selectedKeys;
// selector提供器
private final SelectorProvider provider;
// 选择器策略
private final SelectStrategy selectStrategy;
// 尾部队列
private final Queue<Runnable> tailTasks;
// 任务队列
private final Queue<Runnable> taskQueue;
// 执行器，自定义的，一般可以不传
private final Executor executor;
// 拒绝处理器
private final RejectedExecutionHandler rejectedExecutionHandler;
```

构造方法

```java
// NioEventLoopGroup
public NioEventLoopGroup(int nThreads) {
    this(nThreads, (Executor) null);
}
public NioEventLoopGroup(int nThreads, Executor executor) {
    // 这里选择器提供器是根据不同系统来提供不同的选择器
    this(nThreads, executor, SelectorProvider.provider());
}
public NioEventLoopGroup(
    int nThreads, Executor executor, final SelectorProvider selectorProvider) {
this(nThreads, executor, selectorProvider, DefaultSelectStrategyFactory.INSTANCE);
}

public NioEventLoopGroup(int nThreads, Executor executor, final SelectorProvider selectorProvider,
    final SelectStrategyFactory selectStrategyFactory) {
    super(nThreads, executor, selectorProvider, selectStrategyFactory, RejectedExecutionHandlers.reject());
}

// MultithreadEventExecutorGroup
protected MultithreadEventLoopGroup(int nThreads, Executor executor, Object... args) {
    super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, args);
}
protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
    this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
}

protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                        EventExecutorChooserFactory chooserFactory, Object... args) {
    checkPositive(nThreads, "nThreads");
    if (executor == null) {
        // 如果没有传入则自己创建
        executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
    }
    // 事件处理器数组
    children = new EventExecutor[nThreads];
    for (int i = 0; i < nThreads; i ++) {
        boolean success = false;
        try {
            // 创建多个子的NioEventLoop
            children[i] = newChild(executor, args);
            success = true;
        } catch (Exception e) {
            // TODO: Think about if this is a good exception type
            throw new IllegalStateException("failed to create a child event loop", e);
        } finally {
            // ...
        }
    }
    // 创建选择器
    chooser = chooserFactory.newChooser(children);
    final FutureListener<Object> terminationListener = new FutureListener<Object>() {
        @Override
        public void operationComplete(Future<Object> future) throws Exception {
            if (terminatedChildren.incrementAndGet() == children.length) {
                terminationFuture.setSuccess(null);
            }
        }
    };
    for (EventExecutor e: children) {
        // 添加事件终止事件
        e.terminationFuture().addListener(terminationListener);
    }
    Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
    Collections.addAll(childrenSet, children);
    readonlyChildren = Collections.unmodifiableSet(childrenSet);
}
```

这里主要关注几点

* 自定义执行器 `Executor`如果没有传入，会默认创建
* 根据线程数，创建多个子 `NioEventLoop`，存放于 `children`数组中
* 创建执行器选择器。这个选择器就是用于选择哪个执行器来执行，其中会根据组的大小判断使用哪种选择方式，实现一种负载均衡
* 添加终止监听事件


# 3. 启动器 ServerBootstrap

```java
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel>

// ServerBootstrap
// workGroup
private volatile EventLoopGroup childGroup;

// AbstractBootstrap
// boosGroup
volatile EventLoopGroup group;
```

基本的初始化操作

```java
.channel(NioServerSocketChannel.class)

// 这里其实就是使用反射创建channel
public B channel(Class<? extends C> channelClass) {
    return channelFactory(new ReflectiveChannelFactory<C>(
            ObjectUtil.checkNotNull(channelClass, "channelClass")
    ));
}
```

剩余的操作都是基本的属性设置。



# 4.注册

在之前 `NIO`中进行注册如下

```java
serverChannel.register(selector, SelectionKey.OP_ACCEPT);
```

那么这里就涉及将 `channel`注册到 `selector`上面去，在服务端 `channel`为 `NioServerSocketChannel`，客户端为 `NioSocketChannel`
，于是我们从服务端 `channel`入手，查看注册方法

```java
@Override
protected void doRegister() throws Exception {
    boolean selected = false;
    for (;;) {
        try {
            selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
            return;
        } catch (CancelledKeyException e) {
            // ...
    }
}
```

其他内容暂且不管，可以清晰的看到注册逻辑，很自然的和 `NIO`就结合起来了。同时可以看到其实 `selector`对事件对轮询工作在 `Netty`中是由 `EventLoop`来完成的，我们在之前 `NIO` 中是通过循环进行检查。

```java
// AbstractBootstrap
public ChannelFuture bind(int inetPort) {
    return bind(new InetSocketAddress(inetPort));
}
public ChannelFuture bind(SocketAddress localAddress) {
    validate();
    return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));
}
private ChannelFuture doBind(final SocketAddress localAddress) {
    // 这里就是注册
    final ChannelFuture regFuture = initAndRegister();
    final Channel channel = regFuture.channel();
    if (regFuture.cause() != null) {
        return regFuture;
    }
    // 注册完成
    if (regFuture.isDone()) {
        // At this point we know that the registration was complete and successful.
        ChannelPromise promise = channel.newPromise();
        // 绑定
        doBind0(regFuture, channel, localAddress, promise);
        return promise;
    } else {
        // 一般情况来说注册都是成功的，这里只是以防万一
        // Registration future is almost always fulfilled already, but just in case it's not.
        final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
        regFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                Throwable cause = future.cause();
                if (cause != null) {
                    // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                    // IllegalStateException once we try to access the EventLoop of the Channel.
                    promise.setFailure(cause);
                } else {
                    // Registration was successful, so set the correct executor to use.
                    // See https://github.com/netty/netty/issues/2586
                    promise.registered();

                    doBind0(regFuture, channel, localAddress, promise);
                }
            }
        });
        return promise;
    }
}
```



## 4.1 initAndRegister

下面我们看详细的注册流程

```java
final ChannelFuture initAndRegister() {
    Channel channel = null;
    try {
        // 这里是创建一个channel，一般我们会传入NioServerSocketChannel.class或者NioSocketChannel.class
        // 这里通过反射创建处channel
        channel = channelFactory.newChannel();
        init(channel);
    } catch (Throwable t) {
        if (channel != null) {
            // channel can be null if newChannel crashed (eg SocketException("too many open files"))
            channel.unsafe().closeForcibly();
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
        }
        // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
        return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
    }

    ChannelFuture regFuture = config().group().register(channel);
    if (regFuture.cause() != null) {
        if (channel.isRegistered()) {
            channel.close();
        } else {
            channel.unsafe().closeForcibly();
        }
    }
    return regFuture;
}
```

这里先看下 `NioServerSocketChannel`实例化

```java
public NioServerSocketChannel() {
    // newSocket返回一个ServerSocketChannel
    this(newSocket(DEFAULT_SELECTOR_PROVIDER));
}
public NioServerSocketChannel(ServerSocketChannel channel) {
    super(null, channel, SelectionKey.OP_ACCEPT);
    config = new NioServerSocketChannelConfig(this, javaChannel().socket());
}
private static ServerSocketChannel newSocket(SelectorProvider provider) {
    try {
        return provider.openServerSocketChannel();
    } catch (IOException e) {
        throw new ChannelException(
                "Failed to open a server socket.", e);
    }
}
```

这里默认返回 `ServerSocketChannelImpl`，这样就和 `NIO`联系起来了。

```java
protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
    super(parent);
    this.ch = ch;
    // 设置ready的key值
    this.readInterestOp = readInterestOp;
    try {
        // 设置为非阻塞
        ch.configureBlocking(false);
    } catch (IOException e) {
        // ...
    }
}

protected AbstractChannel(Channel parent) {
    this.parent = parent;
    // channel标识
    id = newId();
    // 用于操作底层数据读写
    unsafe = newUnsafe();
    // 创建一个新的pipeline
    pipeline = newChannelPipeline();
}
```

这里可以看到和我们自己写 `NIO`服务端基本类似，除此之外，还需要注意 `unsafe`和 `pipeline`的设置。

## 4.1.1 pipeline创建

```java
protected DefaultChannelPipeline newChannelPipeline() {
    return new DefaultChannelPipeline(this);
}
protected DefaultChannelPipeline(Channel channel) {
    this.channel = ObjectUtil.checkNotNull(channel, "channel");
    succeededFuture = new SucceededChannelFuture(channel, null);
    voidPromise =  new VoidChannelPromise(channel, true);

    tail = new TailContext(this);
    head = new HeadContext(this);

    head.next = tail;
    tail.prev = head;
}
```

其实就是一个双向链表，同时单独维护链表头和尾。


### 4.1.2 init(channel)

```java
void init(Channel channel) {
    // 这里就是通过option方法设置的
    setChannelOptions(channel, newOptionsArray(), logger);
    // 这里是通过attr方法设置的
    setAttributes(channel, newAttributesArray());

    // 这里获取channel上面的pipeline，在实例化channel的时候就会创建pipeline，
    // 是一个双向链表结构
    ChannelPipeline p = channel.pipeline();

    final EventLoopGroup currentChildGroup = childGroup;
    final ChannelHandler currentChildHandler = childHandler;
    final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions);
    final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs);

    p.addLast(new ChannelInitializer<Channel>() {
        @Override
        public void initChannel(final Channel ch) {
            final ChannelPipeline pipeline = ch.pipeline();
            // 这个handler就是我们初始设置的处理handler(.childHandler(...))
            ChannelHandler handler = config.handler();
            if (handler != null) {
                pipeline.addLast(handler);
            }
            // 添加一个ServerBootstrapAcceptor，这样就可以处理入站事件
            ch.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    pipeline.addLast(new ServerBootstrapAcceptor(
                            ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                }
            });
        }
    });
}
```

这里就是一些基本初始化，关键点在 `addLast`方法。首先是将自定义的handler添加到了pipeline中，然后又添加了一个ChannelInitializer。





```java
https://mp.weixin.qq.com/s?__biz=MzkxNTE3NjQ3MA==&mid=2247494305&idx=1&sn=96462d063637e02d9fb2095dd1c06dc1&chksm=c16187d8f6160ece073beeb7ab65018273d667ed459578064b34c536b0963807f31cb800d77e&scene=178&cur_album_id=2126839054972354563#rd

https://blog.csdn.net/wangwei19871103/article/details/104069043?spm=1001.2101.3001.6650.1&utm_medium=distribute.pc_relevant.none-task-blog-2%7Edefault%7EBlogCommendFromBaidu%7ERate-1-104069043-blog-117996140.pc_relevant_multi_platform_whitelistv4&depth_1-utm_source=distribute.pc_relevant.none-task-blog-2%7Edefault%7EBlogCommendFromBaidu%7ERate-1-104069043-blog-117996140.pc_relevant_multi_platform_whitelistv4&utm_relevant_index=2

https://blog.csdn.net/wangwei19871103?type=blog

https://blog.csdn.net/wangwei19871103/article/details/104024594?spm=1001.2014.3001.5502

```








# 5. 绑定
