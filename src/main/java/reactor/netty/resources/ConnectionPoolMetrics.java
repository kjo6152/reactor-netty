package reactor.netty.resources;

import reactor.pool.InstrumentedPool;

public interface ConnectionPoolMetrics {

    /**
     * Measure the current number of resources that have been successfully
     * acquired and are in active use.
     *
     * @return the number of acquired resources
     */
    int acquiredSize();

    /**
     * Measure the current number of allocated resources in the pool, acquired
     * or idle.
     *
     * @return the total number of allocated resources managed by the pool
     */
    int allocatedSize();

    /**
     * Measure the current number of idle resources in the pool.
     * <p>
     * Note that some resources might be lazily evicted when they're next considered
     * for an incoming acquire call. Such resources would still count
     * towards this method.
     *
     * @return the number of idle resources
     */
    int idleSize();

    /**
     * Measure the current number of "pending" acquire Monos in the  Pool.
     * <p>
     * An acquire is in the pending state when it is attempted at a point when no idle
     * resource is available in the pool, and no new resource can be created.
     *
     * @return the number of pending acquire
     */
    int pendingAcquireSize();

    /**
     * Get the maximum number of live resources this pool will allow.
     * <p>
     * A pool might be unbounded, in which case this method returns {@link Integer#MAX_VALUE}.
     *
     * @return the maximum number of live resources that can be allocated by this pool
     */
    int getMaxAllocatedSize();

    /**
     * Get the maximum number of acquire this pool can queue in a pending state when no available
     * resource is immediately handy (and the pool cannot allocate more resources).
     * <p>
     * A pool pending queue might be unbounded, in which case this method returns
     * {@link Integer#MAX_VALUE}.
     *
     * @return the maximum number of pending acquire that can be enqueued by this pool
     */
    int getMaxPendingAcquireSize();

    class ConnectionPoolWrapper implements ConnectionPoolMetrics {

        private final InstrumentedPool.PoolMetrics delegate;

        public ConnectionPoolWrapper(InstrumentedPool.PoolMetrics delegate) {
            this.delegate = delegate;
        }

        @Override
        public int acquiredSize() {
            return delegate.acquiredSize();
        }

        @Override
        public int allocatedSize() {
            return delegate.allocatedSize();
        }

        @Override
        public int idleSize() {
            return delegate.idleSize();
        }

        @Override
        public int pendingAcquireSize() {
            return delegate.pendingAcquireSize();
        }

        @Override
        public int getMaxAllocatedSize() {
            return delegate.getMaxAllocatedSize();
        }

        @Override
        public int getMaxPendingAcquireSize() {
            return delegate.getMaxPendingAcquireSize();
        }
    }

}
