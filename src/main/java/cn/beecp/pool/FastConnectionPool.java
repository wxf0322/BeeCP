/*
 * Copyright Chris2018998
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.beecp.pool;

import cn.beecp.BeeDataSourceConfig;
import cn.beecp.ConnectionFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static cn.beecp.pool.PoolStaticCenter.*;
import static java.lang.System.*;
import static java.util.concurrent.TimeUnit.*;
import static java.util.concurrent.locks.LockSupport.*;

/**
 * JDBC Connection Pool Implementation
 *
 * @author Chris.Liao
 * @version 1.0
 */
public final class FastConnectionPool extends Thread implements ConnectionPool, ConnectionPoolJMXBean {
    private static final long spinForTimeoutThreshold = 1000L;
    private static final int maxTimedSpins = (Runtime.getRuntime().availableProcessors() < 2) ? 0 : 32;
    private static final AtomicIntegerFieldUpdater<PooledConnection> ConnStUpd = AtomicIntegerFieldUpdater.newUpdater(PooledConnection.class, "state");
    private static final AtomicReferenceFieldUpdater<Borrower, Object> BwrStUpd = AtomicReferenceFieldUpdater.newUpdater(Borrower.class, Object.class, "state");
    private static final String DESC_REMOVE_INIT = "init";
    private static final String DESC_REMOVE_BAD = "bad";
    private static final String DESC_REMOVE_IDLE = "idle";
    private static final String DESC_REMOVE_CLOSED = "closed";
    private static final String DESC_REMOVE_RESET = "reset";
    private static final String DESC_REMOVE_DESTROY = "destroy";
    private static final AtomicInteger poolNameIndex = new AtomicInteger(1);
    private final Object connArrayLock = new Object();
    private final Object connNotifyLock = new Object();
    private final ConcurrentLinkedQueue<Borrower> waitQueue = new ConcurrentLinkedQueue<Borrower>();
    private final ThreadLocal<WeakReference<Borrower>> threadLocal = new ThreadLocal<WeakReference<Borrower>>();
    private final ConnectionPoolMonitorVo monitorVo = new ConnectionPoolMonitorVo();

    private int poolMaxSize;
    private long defaultMaxWaitNanos;//nanoseconds
    private int conUnCatchStateCode;
    private int connectionTestTimeout;//seconds
    private long connectionTestInterval;//milliseconds
    private ConnectionPoolHook exitHook;
    private BeeDataSourceConfig poolConfig;
    private Semaphore borrowSemaphore;
    private TransferPolicy transferPolicy;
    private ConnectionTestPolicy testPolicy;
    private ConnectionFactory connFactory;
    private volatile PooledConnection[] connArray = new PooledConnection[0];
    private ScheduledFuture<?> idleCheckSchFuture;
    private ScheduledThreadPoolExecutor idleSchExecutor = new ScheduledThreadPoolExecutor(2, new PoolThreadThreadFactory("IdleConnectionScan"));
    private int networkTimeout;
    private boolean supportSchema = true;
    private boolean supportNetworkTimeout = true;
    private boolean supportQueryTimeout = true;
    private boolean supportIsValid = true;
    private String poolName = "";
    private String poolMode = "";
    private AtomicInteger poolState = new AtomicInteger(POOL_UNINIT);
    private AtomicInteger createConnThreadState = new AtomicInteger(THREAD_WORKING);
    private AtomicInteger needAddConnSize = new AtomicInteger(0);

    /**
     * initialize pool with configuration
     *
     * @param config data source configuration
     * @throws SQLException check configuration fail or to create initiated connection
     */
    public void init(BeeDataSourceConfig config) throws SQLException {
        if (poolState.get() == POOL_UNINIT) {
            checkProxyClasses();
            if (config == null) throw new SQLException("DataSource configuration can't be null");
            poolConfig = config;

            poolName = !isBlank(config.getPoolName()) ? config.getPoolName() : "FastPool-" + poolNameIndex.getAndIncrement();
            commonLog.info("BeeCP({})starting....", poolName);

            poolMaxSize = poolConfig.getMaxActive();
            connFactory = poolConfig.getConnectionFactory();
            connectionTestTimeout = poolConfig.getConnectionTestTimeout();
            this.testPolicy = new SQLQueryTestPolicy(poolConfig.isDefaultAutoCommit(), poolConfig.getConnectionTestSQL());

            defaultMaxWaitNanos = MILLISECONDS.toNanos(poolConfig.getMaxWait());
            connectionTestInterval = poolConfig.getConnectionTestInterval();
            createInitConnections(poolConfig.getInitialSize());

            if (poolConfig.isFairMode()) {
                poolMode = "fair";
                transferPolicy = new FairTransferPolicy();
                conUnCatchStateCode = transferPolicy.getCheckStateCode();
            } else {
                poolMode = "compete";
                transferPolicy = new CompeteTransferPolicy();
                conUnCatchStateCode = transferPolicy.getCheckStateCode();
            }

            exitHook = new ConnectionPoolHook();
            Runtime.getRuntime().addShutdownHook(exitHook);
            borrowSemaphore = new Semaphore(poolConfig.getBorrowSemaphoreSize(), poolConfig.isFairMode());
            idleSchExecutor.setKeepAliveTime(15, SECONDS);
            idleSchExecutor.allowCoreThreadTimeOut(true);
            idleCheckSchFuture = idleSchExecutor.scheduleAtFixedRate(new Runnable() {
                public void run() {// check idle connection
                    closeIdleTimeoutConnection();
                }
            }, config.getIdleCheckTimeInitDelay(), config.getIdleCheckTimeInterval(), TimeUnit.MILLISECONDS);

            registerJMX();
            commonLog.info("BeeCP({})has startup{mode:{},init size:{},max size:{},semaphore size:{},max wait:{}ms,driver:{}}",
                    poolName,
                    poolMode,
                    connArray.length,
                    config.getMaxActive(),
                    poolConfig.getBorrowSemaphoreSize(),
                    poolConfig.getMaxWait(),
                    poolConfig.getDriverClassName());

            poolState.set(POOL_NORMAL);
            this.setDaemon(true);
            this.setName("PooledConnectionAdd");
            this.start();
        } else {
            throw new SQLException("Pool has initialized");
        }
    }

    /**
     * check some proxy classes whether exists
     */
    private void checkProxyClasses() throws SQLException {
        try {
            ClassLoader classLoader = getClass().getClassLoader();
            Class.forName("cn.beecp.pool.Borrower", true, classLoader);
            Class.forName("cn.beecp.pool.PooledConnection", true, classLoader);
            Class.forName("cn.beecp.pool.ProxyConnection", true, classLoader);
            Class.forName("cn.beecp.pool.ProxyStatement", true, classLoader);
            Class.forName("cn.beecp.pool.ProxyPsStatement", true, classLoader);
            Class.forName("cn.beecp.pool.ProxyCsStatement", true, classLoader);
            Class.forName("cn.beecp.pool.ProxyDatabaseMetaData", true, classLoader);
            Class.forName("cn.beecp.pool.ProxyResultSet", true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new SQLException("Jdbc proxy classes missed", e);
        }
    }

    boolean supportIsValid() {
        return supportIsValid;
    }

    boolean supportSchema() {
        return supportSchema;
    }

    boolean supportNetworkTimeout() {
        return supportNetworkTimeout;
    }

    int getNetworkTimeout() {
        return networkTimeout;
    }

    ThreadPoolExecutor getNetworkTimeoutExecutor() {
        return idleSchExecutor;
    }

    private boolean existBorrower() {
        return poolConfig.getBorrowSemaphoreSize() > borrowSemaphore.availablePermits() || borrowSemaphore.hasQueuedThreads();
    }

    //create Pooled connection
    private PooledConnection createPooledConn(int connState) throws SQLException {
        synchronized (connArrayLock) {
            int arrayLen = connArray.length;
            if (arrayLen < poolMaxSize) {
                Connection con = connFactory.create();
                setDefaultOnRawConn(con);
                PooledConnection pConn = new PooledConnection(con, connState, this, poolConfig);// add
                PooledConnection[] arrayNew = new PooledConnection[arrayLen + 1];
                arraycopy(connArray, 0, arrayNew, 0, arrayLen);
                arrayNew[arrayLen] = pConn;// tail
                connArray = arrayNew;
                return pConn;
            } else {
                return null;
            }
        }
    }

    //remove Pooled connection
    private void removePooledConn(PooledConnection pConn, String removeType) {
        pConn.state = CONNECTION_CLOSED;
        pConn.closeRawConn();
        synchronized (connArrayLock) {
            int oldLen = connArray.length;
            PooledConnection[] arrayNew = new PooledConnection[oldLen - 1];
            for (int i = 0; i < oldLen; i++) {
                if (connArray[i] == pConn) {
                    arraycopy(connArray, 0, arrayNew, 0, i);
                    int m = oldLen - i - 1;
                    if (m > 0) arraycopy(connArray, i + 1, arrayNew, i, m);
                    break;
                }
            }
            connArray = arrayNew;
        }
    }

    //set default attribute on raw connection
    private void setDefaultOnRawConn(Connection rawConn) {
        try {
            rawConn.setAutoCommit(poolConfig.isDefaultAutoCommit());
        } catch (Throwable e) {
            commonLog.warn("BeeCP({})failed to set default on executing 'setAutoCommit'", poolName);
        }

        try {
            rawConn.setTransactionIsolation(poolConfig.getDefaultTransactionIsolationCode());
        } catch (SQLException e) {
            commonLog.warn("BeeCP({}))failed to set default on executing to 'setTransactionIsolation'", poolName);
        }

        try {
            rawConn.setReadOnly(poolConfig.isDefaultReadOnly());
        } catch (Throwable e) {
            commonLog.warn("BeeCP({}))failed to set default on executing to 'setReadOnly'", poolName);
        }

        if (!isBlank(poolConfig.getDefaultCatalog())) {
            try {
                rawConn.setCatalog(poolConfig.getDefaultCatalog());
            } catch (Throwable e) {
                commonLog.warn("BeeCP({}))failed to set default on executing to 'setCatalog'", poolName);
            }
        }

        //for JDK1.7 begin
        if (supportSchema && !isBlank(poolConfig.getDefaultSchema())) {//test schema
            try {
                rawConn.setSchema(poolConfig.getDefaultSchema());
            } catch (Throwable e) {
                supportSchema = false;
                commonLog.warn("BeeCP({})driver not support 'schema'", poolName);
            }
        }

        if (supportNetworkTimeout) {//test networkTimeout
            try {//set networkTimeout
                this.networkTimeout = rawConn.getNetworkTimeout();
                if (networkTimeout < 0) {
                    supportNetworkTimeout = false;
                    commonLog.warn("BeeCP({})driver not support 'networkTimeout'", poolName);
                } else {
                    rawConn.setNetworkTimeout(this.getNetworkTimeoutExecutor(), networkTimeout);
                }
            } catch (Throwable e) {
                supportNetworkTimeout = false;
                commonLog.warn("BeeCP({})driver not support 'networkTimeout'", poolName);
            }
        }

        if (this.supportIsValid) {//test isValid
            try {//test Connection.isValid
                if (rawConn.isValid(connectionTestTimeout)) {
                    this.testPolicy = new ConnValidTestPolicy();
                } else {
                    supportIsValid = false;
                    commonLog.warn("BeeCP({})driver not support 'isValid'", poolName);
                }
            } catch (Throwable e) {
                supportIsValid = false;
                commonLog.warn("BeeCP({})driver not support 'isValid'", poolName);
                Statement st = null;
                try {
                    st = rawConn.createStatement();
                    st.setQueryTimeout(connectionTestTimeout);
                } catch (Throwable ee) {
                    supportQueryTimeout = false;
                    commonLog.warn("BeeCP({})driver not support 'queryTimeout'", poolName);
                } finally {
                    if (st != null) oclose(st);
                }
            }
        }
        //for JDK1.7 end
    }

    /**
     * check connection state
     *
     * @return if the checked connection is active then return true,otherwise
     * false if false then close it
     */
    private final boolean testOnBorrow(PooledConnection pConn) {
        if (currentTimeMillis() - pConn.lastAccessTime - connectionTestInterval < 0 || testPolicy.isActive(pConn))
            return true;

        removePooledConn(pConn, DESC_REMOVE_BAD);
        tryToCreateNewConnByAsyn();
        return false;
    }

    /**
     * create initialization connections
     *
     * @throws SQLException error occurred in creating connections
     */
    private void createInitConnections(int initSize) throws SQLException {
        try {
            for (int i = 0; i < initSize; i++)
                createPooledConn(CONNECTION_IDLE);
        } catch (SQLException e) {
            for (PooledConnection pConn : connArray)
                removePooledConn(pConn, DESC_REMOVE_INIT);
            throw e;
        }
    }

    /**
     * borrow one connection from pool
     *
     * @return If exists idle connection in pool,then return one;if not, waiting
     * until other borrower release
     * @throws SQLException if pool is closed or waiting timeout,then throw exception
     */
    public Connection getConnection() throws SQLException {
        if (poolState.get() != POOL_NORMAL) throw PoolCloseException;

        //0:try to get from threadLocal cache
        WeakReference<Borrower> ref = threadLocal.get();
        Borrower borrower = (ref != null) ? ref.get() : null;
        if (borrower != null) {
            PooledConnection pConn = borrower.lastUsedConn;
            if (pConn != null && ConnStUpd.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_USING)) {
                if (testOnBorrow(pConn)) return createProxyConnection(pConn, borrower);

                borrower.lastUsedConn = null;
            }
        } else {
            borrower = new Borrower();
            threadLocal.set(new WeakReference<Borrower>(borrower));
        }


        long deadline = nanoTime() + defaultMaxWaitNanos;
        try {
            if (!borrowSemaphore.tryAcquire(this.defaultMaxWaitNanos, NANOSECONDS))
                throw RequestTimeoutException;
        } catch (InterruptedException e) {
            throw RequestInterruptException;
        }

        try {//borrowSemaphore acquired
            //1:try to search one from array
            for (PooledConnection pConn : connArray) {
                if (ConnStUpd.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_USING) && testOnBorrow(pConn))
                    return createProxyConnection(pConn, borrower);
            }

            //2:try to create one directly
            PooledConnection pConn;
            if (connArray.length < poolMaxSize && (pConn = createPooledConn(CONNECTION_USING)) != null)
                return createProxyConnection(pConn, borrower);

            //3:try to get one transferred connection
            boolean failed = false;
            borrower.state = BORROWER_NORMAL;
            SQLException failedCause = RequestTimeoutException;

            waitQueue.offer(borrower);
            int spinSize = (waitQueue.peek() == borrower) ? maxTimedSpins : 0;
            while (true) {
                Object state = borrower.state;
                if (state instanceof PooledConnection) {
                    pConn = (PooledConnection) state;
                    if (transferPolicy.tryCatch(pConn) && testOnBorrow(pConn)) {
                        waitQueue.remove(borrower);
                        return createProxyConnection(pConn, borrower);
                    }

                    borrower.state = BORROWER_NORMAL;
                    yield();
                } else if (state instanceof SQLException) {
                    waitQueue.remove(borrower);
                    throw (SQLException) state;
                }

                if (failed) {
                    BwrStUpd.compareAndSet(borrower, state, failedCause);
                } else {
                    long timeout;
                    if ((timeout = deadline - nanoTime()) > 0L) {
                        if (spinSize > 0) {
                            --spinSize;
                        } else if (timeout > spinForTimeoutThreshold && BwrStUpd.compareAndSet(borrower, state, BORROWER_WAITING)) {
                            parkNanos(this, timeout);
                            if (borrower.thread.isInterrupted()) {
                                failed = true;
                                failedCause = RequestInterruptException;
                            }
                        }
                    } else {//timeout
                        failed = true;
                    }
                }
            }//while
        } finally {
            borrowSemaphore.release();
        }
    }

    /**
     * remove connection
     *
     * @param pConn target connection need release
     */
    void abandonOnReturn(PooledConnection pConn) {
        removePooledConn(pConn, DESC_REMOVE_BAD);
        tryToCreateNewConnByAsyn();
    }

    /**
     * return connection to pool
     *
     * @param pConn target connection need release
     */
    public final void recycle(PooledConnection pConn) {
        transferPolicy.beforeTransfer(pConn);
        for (Borrower borrower : waitQueue)
            for (Object state = borrower.state; state == BORROWER_NORMAL || state == BORROWER_WAITING; state = borrower.state) {
                if (pConn.state - conUnCatchStateCode != 0) return;
                if (BwrStUpd.compareAndSet(borrower, state, pConn)) {//transfer successful
                    if (state == BORROWER_WAITING) unpark(borrower.thread);
                    return;
                }
            }
        transferPolicy.onFailedTransfer(pConn);
    }

    /**
     * @param exception: transfer Exception to waiter
     */
    private void transferException(SQLException exception) {
        for (Borrower borrower : waitQueue)
            for (Object state = borrower.state; state == BORROWER_NORMAL || state == BORROWER_WAITING; state = borrower.state) {
                if (BwrStUpd.compareAndSet(borrower, state, exception)) {//transfer successful
                    if (state == BORROWER_WAITING) unpark(borrower.thread);
                    return;
                }
            }
    }

    /**
     * inner timer will call the method to clear some idle timeout connections
     * or dead connections,or long time not active connections in using state
     */
    private void closeIdleTimeoutConnection() {
        if (poolState.get() == POOL_NORMAL) {
            PooledConnection[] array = connArray;
            for (int i = 0, len = array.length; i < len; i++) {
                PooledConnection pConn = array[i];
                int state = pConn.state;
                if (state == CONNECTION_IDLE && !existBorrower()) {
                    boolean isTimeoutInIdle = (currentTimeMillis() - pConn.lastAccessTime - poolConfig.getIdleTimeout() >= 0);
                    if (isTimeoutInIdle && ConnStUpd.compareAndSet(pConn, state, CONNECTION_CLOSED)) {//need close idle
                        removePooledConn(pConn, DESC_REMOVE_IDLE);
                        tryToCreateNewConnByAsyn();
                    }
                } else if (state == CONNECTION_USING) {
                    ProxyConnectionBase proxyConn = pConn.proxyConn;
                    boolean isHoldTimeoutInNotUsing = currentTimeMillis() - pConn.lastAccessTime - poolConfig.getHoldTimeout() >= 0;
                    if (isHoldTimeoutInNotUsing && proxyConn != null) {//recycle connection
                        proxyConn.trySetAsClosed();
                    }
                } else if (state == CONNECTION_CLOSED) {
                    removePooledConn(pConn, DESC_REMOVE_CLOSED);
                    tryToCreateNewConnByAsyn();
                }
            }
        }
    }

    // shutdown pool
    public void close() throws SQLException {
        long parkNanoSeconds = SECONDS.toNanos(poolConfig.getWaitTimeToClearPool());
        while (true) {
            if (poolState.compareAndSet(POOL_NORMAL, POOL_CLOSED)) {
                commonLog.info("BeeCP({})begin to shutdown", poolName);
                removeAllConnections(poolConfig.isForceCloseConnection(), DESC_REMOVE_DESTROY);
                unregisterJMX();
                shutdownCreateConnThread();
                while (!idleCheckSchFuture.isCancelled() && !idleCheckSchFuture.isDone())
                    idleCheckSchFuture.cancel(true);
                idleSchExecutor.shutdownNow();
                try {
                    Runtime.getRuntime().removeShutdownHook(exitHook);
                } catch (Throwable e) {
                }

                commonLog.info("BeeCP({})has shutdown", poolName);
                break;
            } else if (poolState.get() == POOL_CLOSED) {
                break;
            } else {
                parkNanos(parkNanoSeconds);// wait 3 seconds
            }
        }
    }

    public boolean isClosed() {
        return poolState.get() == POOL_CLOSED;
    }

    // remove all connections
    private void removeAllConnections(boolean force, String source) {
        while (existBorrower()) {
            transferException(PoolCloseException);
        }

        long parkNanoSeconds = SECONDS.toNanos(poolConfig.getWaitTimeToClearPool());
        while (connArray.length > 0) {
            PooledConnection[] array = connArray;
            for (int i = 0, len = array.length; i < len; i++) {
                PooledConnection pConn = array[i];
                if (ConnStUpd.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_CLOSED)) {
                    removePooledConn(pConn, source);
                } else if (pConn.state == CONNECTION_CLOSED) {
                    removePooledConn(pConn, source);
                } else if (pConn.state == CONNECTION_USING) {
                    ProxyConnectionBase proxyConn = pConn.proxyConn;
                    if (force) {
                        if (proxyConn != null) {
                            proxyConn.trySetAsClosed();
                        }
                    } else {
                        boolean isTimeout = (currentTimeMillis() - pConn.lastAccessTime - poolConfig.getHoldTimeout() >= 0);
                        if (isTimeout && proxyConn != null) {
                            proxyConn.trySetAsClosed();
                        }
                    }
                }
            } // for

            if (connArray.length > 0) parkNanos(parkNanoSeconds);
        } // while
        idleSchExecutor.getQueue().clear();
    }

    // notify to create connections to pool
    private void tryToCreateNewConnByAsyn() {
        if (connArray.length + needAddConnSize.get() < poolMaxSize) {
            synchronized (connNotifyLock) {
                if (connArray.length + needAddConnSize.get() < poolMaxSize) {
                    needAddConnSize.incrementAndGet();
                    if (createConnThreadState.compareAndSet(THREAD_WAITING, THREAD_WORKING))
                        unpark(this);
                }
            }
        }
    }

    // exit connection creation thread
    private void shutdownCreateConnThread() {
        int curSts;
        while (true) {
            curSts = createConnThreadState.get();
            if ((curSts == THREAD_WORKING || curSts == THREAD_WAITING) && createConnThreadState.compareAndSet(curSts, THREAD_DEAD)) {
                if (curSts == THREAD_WAITING) unpark(this);
                break;
            }
        }
    }

    // create connection to pool
    public void run() {
        PooledConnection pConn;
        while (true) {
            while (needAddConnSize.get() > 0) {
                needAddConnSize.decrementAndGet();
                if (!waitQueue.isEmpty()) {
                    try {
                        if ((pConn = createPooledConn(CONNECTION_USING)) != null)
                            recycle(pConn);
                    } catch (SQLException e) {
                        transferException(e);
                    }
                }
            }

            if (needAddConnSize.get() == 0 && createConnThreadState.compareAndSet(THREAD_WORKING, THREAD_WAITING))
                park(this);
            if (createConnThreadState.get() == THREAD_DEAD) break;
        }
    }

    /******************************** JMX **************************************/
    // close all connections
    public void reset() {
        reset(false);// wait borrower release connection,then close them
    }

    // close all connections
    public void reset(boolean force) {
        if (poolState.compareAndSet(POOL_NORMAL, POOL_RESTING)) {
            commonLog.info("BeeCP({})begin to reset.", poolName);
            removeAllConnections(force, DESC_REMOVE_RESET);
            commonLog.info("All pooledConn were cleared");
            poolState.set(POOL_NORMAL);// restore state;
            commonLog.info("BeeCP({})finished reseting", poolName);
        }
    }

    public int getConnTotalSize() {
        return connArray.length;
    }

    public int getConnIdleSize() {
        int idleConnections = 0;
        for (PooledConnection pConn : this.connArray) {
            if (pConn.state == CONNECTION_IDLE)
                idleConnections++;
        }
        return idleConnections;
    }

    public int getConnUsingSize() {
        int active = connArray.length - getConnIdleSize();
        return (active > 0) ? active : 0;
    }

    public int getSemaphoreAcquiredSize() {
        return poolConfig.getBorrowSemaphoreSize() - borrowSemaphore.availablePermits();
    }

    public int getSemaphoreWaitingSize() {
        return borrowSemaphore.getQueueLength();
    }

    public int getTransferWaitingSize() {
        return waitQueue.size();
    }

    public ConnectionPoolMonitorVo getMonitorVo() {
        int totSize = getConnTotalSize();
        int idleSize = getConnIdleSize();
        monitorVo.setPoolName(poolName);
        monitorVo.setPoolMode(poolMode);
        monitorVo.setPoolState(poolState.get());
        monitorVo.setMaxActive(poolMaxSize);
        monitorVo.setIdleSize(idleSize);
        monitorVo.setUsingSize(totSize - idleSize);
        monitorVo.setSemaphoreWaiterSize(getSemaphoreWaitingSize());
        monitorVo.setTransferWaiterSize(getTransferWaitingSize());
        return monitorVo;
    }

    // register JMX
    private void registerJMX() {
        if (poolConfig.isEnableJMX()) {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            registerJMXBean(mBeanServer, String.format("cn.beecp.pool.FastConnectionPool:type=BeeCP(%s)", poolName), this);
            registerJMXBean(mBeanServer, String.format("cn.beecp.BeeDataSourceConfig:type=BeeCP(%s)-config", poolName), poolConfig);
        }
    }

    private void registerJMXBean(MBeanServer mBeanServer, String regName, Object bean) {
        try {
            ObjectName jmxRegName = new ObjectName(regName);
            if (!mBeanServer.isRegistered(jmxRegName)) {
                mBeanServer.registerMBean(bean, jmxRegName);
            }
        } catch (Exception e) {
            commonLog.warn("BeeCP({})failed to register jmx-bean:{}", poolName, regName, e);
        }
    }

    // unregister JMX
    private void unregisterJMX() {
        if (poolConfig.isEnableJMX()) {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            unregisterJMXBean(mBeanServer, String.format("cn.beecp.pool.FastConnectionPool:type=BeeCP(%s)", poolName));
            unregisterJMXBean(mBeanServer, String.format("cn.beecp.BeeDataSourceConfig:type=BeeCP(%s)-config", poolName));
        }
    }

    private void unregisterJMXBean(MBeanServer mBeanServer, String regName) {
        try {
            ObjectName jmxRegName = new ObjectName(regName);
            if (mBeanServer.isRegistered(jmxRegName)) {
                mBeanServer.unregisterMBean(jmxRegName);
            }
        } catch (Exception e) {
            commonLog.warn("BeeCP({})failed to unregister jmx-bean:{}", poolName, regName, e);
        }
    }

    // Connection check Policy
    static interface ConnectionTestPolicy {
        boolean isActive(PooledConnection pConn);
    }

    // Transfer Policy
    static interface TransferPolicy {
        int getCheckStateCode();

        void beforeTransfer(PooledConnection pConn);

        boolean tryCatch(PooledConnection pConn);

        void onFailedTransfer(PooledConnection pConn);
    }

    static final class PoolThreadThreadFactory implements ThreadFactory {
        private String thName;

        public PoolThreadThreadFactory(String thName) {
            this.thName = thName;
        }

        public Thread newThread(Runnable r) {
            Thread th = new Thread(r, thName);
            th.setDaemon(true);
            return th;
        }
    }

    //******************************** JMX **************************************/
    static final class CompeteTransferPolicy implements TransferPolicy {
        public final int getCheckStateCode() {
            return CONNECTION_IDLE;
        }

        public final boolean tryCatch(PooledConnection pConn) {
            return ConnStUpd.compareAndSet(pConn, CONNECTION_IDLE, CONNECTION_USING);
        }

        public final void onFailedTransfer(PooledConnection pConn) {
        }

        public final void beforeTransfer(PooledConnection pConn) {
            pConn.state = CONNECTION_IDLE;
        }
    }

    static final class FairTransferPolicy implements TransferPolicy {
        public final int getCheckStateCode() {
            return CONNECTION_USING;
        }

        public final boolean tryCatch(PooledConnection pConn) {
            return pConn.state == CONNECTION_USING;
        }

        public final void onFailedTransfer(PooledConnection pConn) {
            pConn.state = CONNECTION_IDLE;
        }

        public final void beforeTransfer(PooledConnection pConn) {
        }
    }

    /**
     * Hook when JVM exit
     */
    private class ConnectionPoolHook extends Thread {
        public void run() {
            try {
                FastConnectionPool.this.close();
            } catch (SQLException e) {
                commonLog.error("Error on closing connection pool,cause:", e);
            }
        }
    }

    // SQL check Policy
    class SQLQueryTestPolicy implements ConnectionTestPolicy {
        private final boolean autoCommit;
        private final String aliveTestSQL;

        public SQLQueryTestPolicy(boolean autoCommit, String aliveTestSQL) {
            this.autoCommit = autoCommit;
            this.aliveTestSQL = aliveTestSQL;
        }

        public boolean isActive(PooledConnection pConn) {
            boolean autoCommitChged = false;
            Statement st = null;
            Connection con = pConn.rawConn;
            try {
                //may be a store procedure or a function in this test sql,so need rollback finally
                //for example: select xxx() from dual
                if (autoCommit) {
                    con.setAutoCommit(false);
                    autoCommitChged = true;
                }

                st = con.createStatement();
                pConn.lastAccessTime = currentTimeMillis();
                if (supportQueryTimeout) {
                    try {
                        st.setQueryTimeout(connectionTestTimeout);
                    } catch (Throwable e) {
                        commonLog.error("BeeCP({})failed to setQueryTimeout", poolName, e);
                    }
                }

                st.execute(aliveTestSQL);

                con.rollback();//why? maybe store procedure in test sql
                return true;
            } catch (Throwable e) {
                commonLog.error("BeeCP({})failed to test connection", poolName, e);
                return false;
            } finally {
                if (st != null) oclose(st);
                if (autoCommit && autoCommitChged) {
                    try {
                        con.setAutoCommit(true);
                    } catch (Throwable e) {
                        commonLog.error("BeeCP({})failed to execute 'rollback or setAutoCommit(true)' after connection test", poolName, e);
                    }
                }
            }
        }
    }

    //check Policy(call connection.isValid)
    class ConnValidTestPolicy implements ConnectionTestPolicy {
        public boolean isActive(PooledConnection pConn) {
            Connection con = pConn.rawConn;
            try {
                if (con.isValid(connectionTestTimeout)) {
                    pConn.lastAccessTime = currentTimeMillis();
                    return true;
                }
            } catch (Throwable e) {
                commonLog.error("BeeCP({})failed to test connection", poolName, e);
            }
            return false;
        }
    }
}
