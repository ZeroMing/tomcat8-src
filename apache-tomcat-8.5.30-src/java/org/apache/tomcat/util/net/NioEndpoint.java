/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private ServerSocketChannel serverSock = null;

    /**
     * 齐步锁
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     * 事件
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        }catch ( Exception x ) {
            log.error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }


    /**
     * Priority of the poller threads.
     * poller线程优先级
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * Poller thread count.
     */
    private int pollerThreadCount = Math.min(2,Runtime.getRuntime().availableProcessors());
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    private long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }
    /**
     * The socket poller.
     */
    private Poller[] pollers = null;
    private AtomicInteger pollerRotater = new AtomicInteger(0);
    /**
     * Return an available poller in true round robin fashion.
     *
     * @return The next poller in sequence
     */
    public Poller getPoller0() {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods
    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     *         for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i=0; i<pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     */
    @Override
    public void bind() throws Exception {

        serverSock = ServerSocketChannel.open();
        socketProperties.setProperties(serverSock.socket());
        InetSocketAddress addr = (getAddress()!=null?new InetSocketAddress(getAddress(),getPort()):new InetSocketAddress(getPort()));
        serverSock.socket().bind(addr,getAcceptCount());
        serverSock.configureBlocking(true); //mimic APR behavior

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        setStopLatch(new CountDownLatch(pollerThreadCount));

        // Initialize SSL if needed
        initialiseSsl();

        selectorPool.open();
    }

    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     * 开启 endpoint
     */
    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;
            //
            processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getProcessorCache());
            eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                            socketProperties.getEventCache());
            nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE,
                    socketProperties.getBufferPool());

            // Create worker collection
            if ( getExecutor() == null ) {
                createExecutor();
            }
            //设置最大连接数,默认值为maxConnections = 10000，通过同步器AQS实现。
            initializeConnectionLatch();

            // Start poller threads
            //创建、配置并启动线程Pooler
            pollers = new Poller[getPollerThreadCount()];
            for (int i=0; i<pollers.length; i++) {
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-"+i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }
            //启动Acceptor线程
            startAcceptorThreads();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i=0; pollers!=null && i<pollers.length; i++) {
                if (pollers[i]==null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor();
            eventCache.clear();
            nioChannels.clear();
            processorCache.clear();
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for "+new InetSocketAddress(getAddress(),getPort()));
        }
        if (running) {
            stop();
        }
        // Close server socket
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        destroySsl();
        super.unbind();
        if (getHandler() != null ) {
            getHandler().recycle();
        }
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for "+new InetSocketAddress(getAddress(),getPort()));
        }
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     *
     * 该方法的主要功能是利用传入的SocketChannel参数生成SecureNioChannel或者NioChannel，
     * 然后注册到Poller线程的selector中
     * Process the specified connection.
     * @param socket The socket channel
     * @return <code>true</code> if the socket was correctly configured
     *  and processing may continue, <code>false</code> if the socket needs to be
     *  close immediately
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            //disable blocking, APR style, we are gonna be polling it
            // 非阻塞
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            socketProperties.setProperties(sock);

            NioChannel channel = nioChannels.pop();
            if (channel == null) {
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),
                        socketProperties.getAppWriteBufSize(),
                        socketProperties.getDirectBuffer());
                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                channel.setIOChannel(socket);
                channel.reset();
            }
            //调用Poller的register方法，完成channel的注册。
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("",t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    // --------------------------------------------------- Acceptor Inner Class
    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     * 后台线程监听 TCP/IP 连接或者 与退出的process进行挥手
     *
     *
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {

                // Loop if endpoint is paused
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    // 达到最大连接数。await等待释放connection，在Endpoint的startInterval方法中设置了最大连接数
                    countUpOrAwaitConnection();

                    // socketChannel
                    SocketChannel socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        // We didn't get a socket
                        countDownConnection();
                        if (running) {
                            // Introduce delay if necessary
                            errorDelay = handleExceptionWithDelay(errorDelay);
                            // re-throw
                            throw ioe;
                        } else {
                            break;
                        }
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // Configure the socket
                    if (running && !paused) {
                        // setSocketOptions() will hand the socket off to
                        // an appropriate processor if successful

                        // 设置非阻塞
                        if (!setSocketOptions(socket)) {
                            closeSocket(socket);
                        }
                    } else {
                        closeSocket(socket);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }


        private void closeSocket(SocketChannel socket) {
            countDownConnection();
            try {
                socket.socket().close();
            } catch (IOException ioe)  {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
            try {
                socket.close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
        }
    }


    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }


    private void close(NioChannel socket, SelectionKey key) {
        try {
            if (socket.getPoller().cancelledKey(key) != null) {
                // SocketWrapper (attachment) was removed from the
                // key - recycle the key. This can only happen once
                // per attempted closure so it is used to determine
                // whether or not to return the key to the cache.
                // We do NOT want to do this more than once - see BZ
                // 57340 / 57943.
                if (running && !paused) {
                    if (!nioChannels.push(socket)) {
                        socket.free();
                    }
                }
            }
        } catch (Exception x) {
            log.error("",x);
        }
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     *
     * PollerEvent, cacheable object for poller events to avoid GC
     *
     * Poller模型
     *
     *
     */
    public static class PollerEvent implements Runnable {

        private NioChannel socket;
        private int interestOps;
        private NioSocketWrapper socketWrapper;

        public PollerEvent(NioChannel ch, NioSocketWrapper w, int intOps) {
            reset(ch, w, intOps);
        }

        public void reset(NioChannel ch, NioSocketWrapper w, int intOps) {
            socket = ch;
            interestOps = intOps;
            socketWrapper = w;
        }

        public void reset() {
            reset(null, null, 0);
        }

        @Override
        public void run() {
            // 如果感兴趣集是自定义的OP_REGISTER
            if (interestOps == OP_REGISTER) {
                try {
                    // 注册到Poller的selector上，感兴趣集为 OP_READ
                    socket.getIOChannel().register(
                            socket.getPoller().getSelector(), SelectionKey.OP_READ, socketWrapper);
                } catch (Exception x) {
                    log.error(sm.getString("endpoint.nio.registerFail"), x);
                }
            } else {
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        socket.socketWrapper.getEndpoint().countDownConnection();
                    } else {
                        //获取已连接的通道
                        final NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper != null) {
                            //we are registering the key to start with, reset the fairness counter.
                            int ops = key.interestOps() | interestOps;
                            socketWrapper.interestOps(ops);
                            // 为key添加新的感兴趣集
                            key.interestOps(ops);
                        } else {
                            socket.getPoller().cancelledKey(key);
                        }
                    }
                } catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key);
                    } catch (Exception ignore) {}
                }
            }
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socketWrapper +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller class. 调查者
     * In tomcat, the default HTTP connector is blocking and follows a one thread per connection model.
     * This means that in order to serve 100 concurrent users, it requires 100 active threads.
     * We end up wasting resources (the thread) because connections may not be used heavily,
     * but just enough to avoid a timeout.
     *
     * Opposed to this is the relatively new NIO or non blocking connector.
     *
     * This connector has a couple of poller threads used to
     * keep the connection alive for all connected users while
     * worker threads are called whenever data (a new HTTP request) is available.
     *
     * This model leads to a much better sharing of resources (threads) and
     * a larger number of concurrent users can be served from the same server.
     *
     * 为了支持100 个并发的用户，最终我们决定浪费一些线程的资源【它们的任务不是那么的重要】去避免超时。
     * 与此相对应的是，NIO 或者 非阻塞IO。
     * 这个连接有 一系列 查询线程，用来为所有在线的用户保活连接，无论何时当数据可用，工作线程被调用的时候。
     * 这种模式 可以促使更好的资源共享和高并发的服务。
     *
     *
     * Poolor主要职责是不断轮询其selector，检查准备就绪的socket(有数据可读或可写)，实现io的多路复用。
     *
     */
    public class Poller implements Runnable {

        // Selector
        private Selector selector;

        // 同步队列
        private final SynchronizedQueue<PollerEvent> events =
                new SynchronizedQueue<>();

        private volatile boolean close = false;
        private long nextExpiration = 0;//optimize expiration handling

        private AtomicLong wakeupCounter = new AtomicLong(0);

        private volatile int keyCount = 0;

        public Poller() throws IOException {
            //开启selector
            this.selector = Selector.open();
        }

        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector;}

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        private void addEvent(PollerEvent event) {
            // 写入队列
            events.offer(event);
            //当前计数为-1
            if ( wakeupCounter.incrementAndGet() == 0 )
                // 唤醒阻塞在select方法上的线程
                selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.pop();
            if ( r==null) r = new PollerEvent(socket,null,interestOps);
            else r.reset(socket,null,interestOps);
            addEvent(r);
            if (close) {
                NioEndpoint.NioSocketWrapper ka = (NioEndpoint.NioSocketWrapper)socket.getAttachment();
                processSocket(ka, SocketEvent.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         * 处理 poller的事件队列里的事件
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         */
        public boolean events() {
            boolean result = false;

            PollerEvent pe = null;

            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++ ) {
                result = true;
                try {
                    // 将pollerEvent中的每个socketChannel注册到selector中
                    pe.run();
                    pe.reset();
                    if (running && !paused) {
                        eventCache.push(pe);
                    }
                } catch ( Throwable x ) {
                    log.error("",x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * @param socket    The newly created socket
         */
        public void register(final NioChannel socket) {

            socket.setPoller(this);
            //创建一个Wrapper
            NioSocketWrapper ka = new NioSocketWrapper(socket, NioEndpoint.this);
            socket.setSocketWrapper(ka);
            ka.setPoller(this);
            ka.setReadTimeout(getSocketProperties().getSoTimeout());
            ka.setWriteTimeout(getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            ka.setReadTimeout(getConnectionTimeout());
            ka.setWriteTimeout(getConnectionTimeout());
            //弹出一个event事件
            PollerEvent r = eventCache.pop();
            //感兴趣 读事件
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            if ( r==null) r = new PollerEvent(socket,ka,OP_REGISTER);
            else r.reset(socket,ka,OP_REGISTER);
            //添加到队列
            addEvent(r);
        }

        public NioSocketWrapper cancelledKey(SelectionKey key) {
            NioSocketWrapper ka = null;
            try {
                if ( key == null ) return null;//nothing to do
                ka = (NioSocketWrapper) key.attach(null);
                if (ka != null) {
                    // If attachment is non-null then there may be a current
                    // connection with an associated processor.
                    getHandler().release(ka);
                }
                if (key.isValid()) key.cancel();
                // If it is available, close the NioChannel first which should
                // in turn close the underlying SocketChannel. The NioChannel
                // needs to be closed first, if available, to ensure that TLS
                // connections are shut down cleanly.
                if (ka != null) {
                    try {
                        ka.getSocket().close(true);
                    } catch (Exception e){
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.socketCloseFail"), e);
                        }
                    }
                }
                // The SocketChannel is also available via the SelectionKey. If
                // it hasn't been closed in the block above, close it now.
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka != null) {
                    countDownConnection();
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("",e);
            }
            return ka;
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         */
        @Override
        public void run() {

            // Loop until destroy() is called。循环直到调用销毁方法
            while (true) {

                boolean hasEvents = false;

                try {
                    if (!close) {
                        hasEvents = events();
                        // 说明有事件
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            //if we are here, means we have other stuff to do
                            //do a non blocking select
                            // 非阻塞的 select
                            keyCount = selector.selectNow();
                        } else {
                            //阻塞selector，直到有准备就绪的socket
                            keyCount = selector.select(selectorTimeout);
                        }
                        wakeupCounter.set(0);
                    }
                    if (close) {
                        //该方法遍历了eventqueue中的所有PollorEvent，然后依次调用PollorEvent的run，将socket注册到selector中。
                        events();
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error("",x);
                    continue;
                }
                //either we timed out or we woke up, process events first
                if ( keyCount == 0 ) hasEvents = (hasEvents | events());

                Iterator<SelectionKey> iterator =
                    keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                // // 遍历就绪的socket
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    NioSocketWrapper attachment = (NioSocketWrapper)sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    if (attachment == null) {
                        iterator.remove();
                    } else {
                        iterator.remove();
                        // 处理已连接的套接字
                        processKey(sk, attachment);
                    }
                }//while

                //process timeouts
                timeout(keyCount,hasEvents);
            }//while

            getStopLatch().countDown();
        }

        /**
         * 处理已连接的套接字通道
         * @param sk
         * @param attachment
         */
        protected void processKey(SelectionKey sk, NioSocketWrapper attachment) {
            try {
                if ( close ) {
                    cancelledKey(sk);
                } else if ( sk.isValid() && attachment != null ) {
                    //有读写事件就绪时
                    if (sk.isReadable() || sk.isWritable() ) {
                        // 处理文件
                        if ( attachment.getSendfileData() != null ) {
                            processSendfile(sk,attachment, false);
                        } else {
                            // 解绑事件
                            unreg(sk, attachment, sk.readyOps());
                            boolean closeSocket = false;
                            // Read goes before write
                            if (sk.isReadable()) {
                                // 可读。 调用processSocket方法进一步处理
                                if (!processSocket(attachment, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }
                            // 可写事件
                            if (!closeSocket && sk.isWritable()) {
                                //调用processSocket方法进一步处理
                                if (!processSocket(attachment, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }
                            if (closeSocket) {
                                cancelledKey(sk);
                            }
                        }
                    }
                } else {
                    //invalid key
                    cancelledKey(sk);
                }
            } catch ( CancelledKeyException ckx ) {
                cancelledKey(sk);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("",t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper,
                boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel)?sc:sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining()>0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos,sd.length,wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: "+sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                        case NONE: {
                            if (log.isDebugEnabled()) {
                                log.debug("Send file connection is being closed");
                            }
                            close(sc, sk);
                            break;
                        }
                        case PIPELINED: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, processing pipe-lined data");
                            }
                            if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                close(sc, sk);
                            }
                            break;
                        }
                        case OPEN: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            reg(sk,socketWrapper,SelectionKey.OP_READ);
                            break;
                        }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper.getSocket(),SelectionKey.OP_WRITE);
                    } else {
                        reg(sk,socketWrapper,SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException x) {
                if (log.isDebugEnabled()) log.debug("Unable to complete sendfile request:", x);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error("", t);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            }
        }

        protected void unreg(SelectionKey sk, NioSocketWrapper attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk,attachment,sk.interestOps()& (~readyOps));
        }

        protected void reg(SelectionKey sk, NioSocketWrapper attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            int keycount = 0;
            try {
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper ka = (NioSocketWrapper) key.attachment();
                        if ( ka == null ) {
                            cancelledKey(key); //we don't support any keys without attachments
                        } else if (close) {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key,ka);
                        } else if ((ka.interestOps()&SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                  (ka.interestOps()&SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean isTimedOut = false;
                            // Check for read timeout
                            if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - ka.getLastRead();
                                long timeout = ka.getReadTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            // Check for write timeout
                            if (!isTimedOut && (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - ka.getLastWrite();
                                long timeout = ka.getWriteTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            if (isTimedOut) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate timeout calls
                                ka.setError(new SocketTimeoutException());
                                if (!processSocket(ka, SocketEvent.ERROR, true)) {
                                    cancelledKey(key);
                                }
                            }
                        }
                    }catch ( CancelledKeyException ckx ) {
                        cancelledKey(key);
                    }
                }//for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

    // ---------------------------------------------------- Key Attachment Class
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;

        private Poller poller = null;
        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            pool = endpoint.getSelectorPool();
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() { return poller;}
        public void setPoller(Poller poller){this.poller = poller;}
        public int interestOps() { return interestOps;}
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if ( latch==null || latch.getCount() == 0 ) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if ( latch == null || latch.getCount() == 0 ) {
                return new CountDownLatch(cnt);
            }
            else throw new IllegalStateException("Latch must be at count 0 or null.");
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch,cnt);}
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch,cnt);}

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if ( latch == null ) throw new IllegalStateException("Latch cannot be null");
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout,unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch,timeout,unit);}
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch,timeout,unit);}

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData;}

        public void updateLastWrite() { lastWrite = System.currentTimeMillis(); }
        public long getLastWrite() { return lastWrite; }
        public void updateLastRead() { lastRead = System.currentTimeMillis(); }
        public long getLastRead() { return lastRead; }


        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.
            nRead = fillReadBuffer(block);
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }


        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            int nRead = populateReadBuffer(to);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            if (to.remaining() >= limit) {
                to.limit(to.position() + limit);
                nRead = fillReadBuffer(block, to);
                updateLastRead();
            } else {
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        public void close() throws IOException {
            getSocket().close();
        }


        @Override
        public boolean isClosed() {
            return !getSocket().isOpen();
        }


        private int fillReadBuffer(boolean block) throws IOException {
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }


        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            NioChannel channel = getSocket();
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    NioEndpoint.NioSocketWrapper att = (NioEndpoint.NioSocketWrapper) channel
                            .getAttachment();
                    if (att == null) {
                        throw new IOException("Key must be cancelled.");
                    }
                    nRead = pool.read(to, channel, selector, att.getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                nRead = channel.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }


        @Override
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            long writeTimeout = getWriteTimeout();
            Selector selector = null;
            try {
                selector = pool.get();
            } catch (IOException x) {
                // Ignore
            }
            try {
                pool.write(from, getSocket(), selector, writeTimeout, block);
                if (block) {
                    // Make sure we are flushed
                    do {
                        if (getSocket().flush(true, selector, writeTimeout)) {
                            break;
                        }
                    } while (true);
                }
                updateLastWrite();
            } finally {
                if (selector != null) {
                    pool.put(selector);
                }
            }
            // If there is data left in the buffer the socket will be registered for
            // write further up the stack. This is to ensure the socket is only
            // registered for write once as both container and user code can trigger
            // write registration.
        }


        @Override
        public void registerReadInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(
                    getSocket().getPoller().getSelector());
            // Might as well do the first write on this thread
            return getSocket().getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateRemoteHost() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteHost = inetAddr.getHostName();
                if (remoteAddr == null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            remotePort = getSocket().getIOChannel().socket().getPort();
        }


        @Override
        protected void populateLocalName() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localName = inetAddr.getHostName();
            }
        }


        @Override
        protected void populateLocalAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateLocalPort() {
            localPort = getSocket().getIOChannel().socket().getLocalPort();
        }


        /**
         * {@inheritDoc}
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLSession session = ch.getSslEngine().getSession();
                return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
            } else {
                return null;
            }
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }


        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }
    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     *
     * Worker 线程
     *
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     *
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {

        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        @Override
        protected void doRun() {
            NioChannel socket = socketWrapper.getSocket();
            SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());

            try {
                int handshake = -1;

                try {
                    if (key != null) {
                        if (socket.isHandshakeComplete()) {
                            // No TLS handshaking required. Let the handler
                            // process this socket / event combination.
                            handshake = 0;
                        } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                                event == SocketEvent.ERROR) {
                            // Unable to complete the TLS handshake. Treat it as
                            // if the handshake failed.
                            handshake = -1;
                        } else {
                            handshake = socket.handshake(key.isReadable(), key.isWritable());
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            event = SocketEvent.OPEN_READ;
                        }
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake",x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                if (handshake == 0) {
                    // 状态为开启
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    if (event == null) {
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        state = getHandler().process(socketWrapper, event);
                    }
                    if (state == SocketState.CLOSED) {
                        close(socket, key);
                    }
                } else if (handshake == -1 ) {
                    close(socket, key);
                } else if (handshake == SelectionKey.OP_READ){
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE){
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error("", t);
                socket.getPoller().cancelledKey(key);
            } finally {
                socketWrapper = null;
                event = null;
                //return to cache
                if (running && !paused) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class
    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
