/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *dsf
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;

import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic {@code int} value to represent state. Subclasses
 * must define the protected methods that change this state, and which
 * define what that state means in terms of this object being acquired
 * or released.  Given these, the other methods in this class carry
 * out all queuing and blocking mechanics. Subclasses can maintain
 * other state fields, but only the atomically updated {@code int}
 * value manipulated using methods {@link #getState}, {@link
 * #setState} and {@link #compareAndSetState} is tracked with respect
 * to synchronization.
 *
 * <p>Subclasses should be defined as non-public internal helper
 * classes that are used to implement the synchronization properties
 * of their enclosing class.  Class
 * {@code AbstractQueuedSynchronizer} does not implement any
 * synchronization interface.  Instead it defines methods such as
 * {@link #acquireInterruptibly} that can be invoked as
 * appropriate by concrete locks and related synchronizers to
 * implement their public methods.
 *
 * <p>This class supports either or both a default <em>exclusive</em>
 * mode and a <em>shared</em> mode. When acquired in exclusive mode,
 * attempted acquires by other threads cannot succeed. Shared mode
 * acquires by multiple threads may (but need not) succeed. This class
 * does not &quot;understand&quot; these differences except in the
 * mechanical sense that when a shared mode acquire succeeds, the next
 * waiting thread (if one exists) must also determine whether it can
 * acquire as well. Threads waiting in the different modes share the
 * same FIFO queue. Usually, implementation subclasses support only
 * one of these modes, but both can come into play for example in a
 * {@link ReadWriteLock}. Subclasses that support only exclusive or
 * only shared modes need not define the methods supporting the unused mode.
 *
 * <p>This class defines a nested {@link ConditionObject} class that
 * can be used as a {@link Condition} implementation by subclasses
 * supporting exclusive mode for which method {@link
 * #isHeldExclusively} reports whether synchronization is exclusively
 * held with respect to the current thread, method {@link #release}
 * invoked with the current {@link #getState} value fully releases
 * this object, and {@link #acquire}, given this saved state value,
 * eventually restores this object to its previous acquired state.  No
 * {@code AbstractQueuedSynchronizer} method otherwise creates such a
 * condition, so if this constraint cannot be met, do not use it.  The
 * behavior of {@link ConditionObject} depends of course on the
 * semantics of its synchronizer implementation.
 *
 * <p>This class provides inspection, instrumentation, and monitoring
 * methods for the internal queue, as well as similar methods for
 * condition objects. These can be exported as desired into classes
 * using an {@code AbstractQueuedSynchronizer} for their
 * synchronization mechanics.
 *
 * <p>Serialization of this class stores only the underlying atomic
 * integer maintaining state, so deserialized objects have empty
 * thread queues. Typical subclasses requiring serializability will
 * define a {@code readObject} method that restores this to a known
 * initial state upon deserialization.
 *
 * <h3>Usage</h3>
 *
 * <p>To use this class as the basis of a synchronizer, redefine the
 * following methods, as applicable, by inspecting and/or modifying
 * the synchronization state using {@link #getState}, {@link
 * #setState} and/or {@link #compareAndSetState}:
 *
 * <ul>
 * <li> {@link #tryAcquire}
 * <li> {@link #tryRelease}
 * <li> {@link #tryAcquireShared}
 * <li> {@link #tryReleaseShared}
 * <li> {@link #isHeldExclusively}
 * </ul>
 * <p>
 * Each of these methods by default throws {@link
 * UnsupportedOperationException}.  Implementations of these methods
 * must be internally thread-safe, and should in general be short and
 * not block. Defining these methods is the <em>only</em> supported
 * means of using this class. All other methods are declared
 * {@code final} because they cannot be independently varied.
 *
 * <p>You may also find the inherited methods from {@link
 * AbstractOwnableSynchronizer} useful to keep track of the thread
 * owning an exclusive synchronizer.  You are encouraged to use them
 * -- this enables monitoring and diagnostic tools to assist users in
 * determining which threads hold locks.
 *
 * <p>Even though this class is based on an internal FIFO queue, it
 * does not automatically enforce FIFO acquisition policies.  The core
 * of exclusive synchronization takes the form:
 *
 * <pre>
 * Acquire:
 *     while (!tryAcquire(arg)) {
 *        <em>enqueue thread if it is not already queued</em>;
 *        <em>possibly block current thread</em>;
 *     }
 *
 * Release:
 *     if (tryRelease(arg))
 *        <em>unblock the first queued thread</em>;
 * </pre>
 * <p>
 * (Shared mode is similar but may involve cascading signals.)
 *
 * <p id="barging">Because checks in acquire are invoked before
 * enqueuing, a newly acquiring thread may <em>barge</em> ahead of
 * others that are blocked and queued.  However, you can, if desired,
 * define {@code tryAcquire} and/or {@code tryAcquireShared} to
 * disable barging by internally invoking one or more of the inspection
 * methods, thereby providing a <em>fair</em> FIFO acquisition order.
 * In particular, most fair synchronizers can define {@code tryAcquire}
 * to return {@code false} if {@link #hasQueuedPredecessors} (a method
 * specifically designed to be used by fair synchronizers) returns
 * {@code true}.  Other variations are possible.
 *
 * <p>Throughput and scalability are generally highest for the
 * default barging (also known as <em>greedy</em>,
 * <em>renouncement</em>, and <em>convoy-avoidance</em>) strategy.
 * While this is not guaranteed to be fair or starvation-free, earlier
 * queued threads are allowed to recontend before later queued
 * threads, and each recontention has an unbiased chance to succeed
 * against incoming threads.  Also, while acquires do not
 * &quot;spin&quot; in the usual sense, they may perform multiple
 * invocations of {@code tryAcquire} interspersed with other
 * computations before blocking.  This gives most of the benefits of
 * spins when exclusive synchronization is only briefly held, without
 * most of the liabilities when it isn't. If so desired, you can
 * augment this by preceding calls to acquire methods with
 * "fast-path" checks, possibly prechecking {@link #hasContended}
 * and/or {@link #hasQueuedThreads} to only do so if the synchronizer
 * is likely not to be contended.
 *
 * <p>This class provides an efficient and scalable basis for
 * synchronization in part by specializing its range of use to
 * synchronizers that can rely on {@code int} state, acquire, and
 * release parameters, and an internal FIFO wait queue. When this does
 * not suffice, you can build synchronizers from a lower level using
 * {@link java.util.concurrent.atomic atomic} classes, your own custom
 * {@link java.util.Queue} classes, and {@link LockSupport} blocking
 * support.
 *
 * <h3>Usage Examples</h3>
 *
 * <p>Here is a non-reentrant mutual exclusion lock class that uses
 * the value zero to represent the unlocked state, and one to
 * represent the locked state. While a non-reentrant lock
 * does not strictly require recording of the current owner
 * thread, this class does so anyway to make usage easier to monitor.
 * It also supports conditions and exposes
 * one of the instrumentation methods:
 *
 *  <pre> {@code
 * class Mutex implements Lock, java.io.Serializable {
 *
 *   // Our internal helper class
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     // Reports whether in locked state
 *     protected boolean isHeldExclusively() {
 *       return getState() == 1;
 *     }
 *
 *     // Acquires the lock if state is zero
 *     public boolean tryAcquire(int acquires) {
 *       assert acquires == 1; // Otherwise unused
 *       if (compareAndSetState(0, 1)) {
 *         setExclusiveOwnerThread(Thread.currentThread());
 *         return true;
 *       }
 *       return false;
 *     }
 *
 *     // Releases the lock by setting state to zero
 *     protected boolean tryRelease(int releases) {
 *       assert releases == 1; // Otherwise unused
 *       if (getState() == 0) throw new IllegalMonitorStateException();
 *       setExclusiveOwnerThread(null);
 *       setState(0);
 *       return true;
 *     }
 *
 *     // Provides a Condition
 *     Condition newCondition() { return new ConditionObject(); }
 *
 *     // Deserializes properly
 *     private void readObject(ObjectInputStream s)
 *         throws IOException, ClassNotFoundException {
 *       s.defaultReadObject();
 *       setState(0); // reset to unlocked state
 *     }
 *   }
 *
 *   // The sync object does all the hard work. We just forward to it.
 *   private final Sync sync = new Sync();
 *
 *   public void lock()                { sync.acquire(1); }
 *   public boolean tryLock()          { return sync.tryAcquire(1); }
 *   public void unlock()              { sync.release(1); }
 *   public Condition newCondition()   { return sync.newCondition(); }
 *   public boolean isLocked()         { return sync.isHeldExclusively(); }
 *   public boolean hasQueuedThreads() { return sync.hasQueuedThreads(); }
 *   public void lockInterruptibly() throws InterruptedException {
 *     sync.acquireInterruptibly(1);
 *   }
 *   public boolean tryLock(long timeout, TimeUnit unit)
 *       throws InterruptedException {
 *     return sync.tryAcquireNanos(1, unit.toNanos(timeout));
 *   }
 * }}</pre>
 *
 * <p>Here is a latch class that is like a
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * except that it only requires a single {@code signal} to
 * fire. Because a latch is non-exclusive, it uses the {@code shared}
 * acquire and release methods.
 *
 *  <pre> {@code
 * class BooleanLatch {
 *
 *   private static class Sync extends AbstractQueuedSynchronizer {
 *     boolean isSignalled() { return getState() != 0; }
 *
 *     protected int tryAcquireShared(int ignore) {
 *       return isSignalled() ? 1 : -1;
 *     }
 *
 *     protected boolean tryReleaseShared(int ignore) {
 *       setState(1);
 *       return true;
 *     }
 *   }
 *
 *   private final Sync sync = new Sync();
 *   public boolean isSignalled() { return sync.isSignalled(); }
 *   public void signal()         { sync.releaseShared(1); }
 *   public void await() throws InterruptedException {
 *     sync.acquireSharedInterruptibly(1);
 *   }
 * }}</pre>
 *
 * @author Doug Lea
 * @since 1.5
 */
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer
        implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * Creates a new {@code AbstractQueuedSynchronizer} instance
     * with initial synchronization state of zero.
     */
    protected AbstractQueuedSynchronizer() {
    }

    /**
     * Wait queue node class.
     *
     * <p>The wait queue is a variant of a "CLH" (Craig, Landin, and
     * Hagersten) lock queue. CLH locks are normally used for
     * spinlocks.  We instead use them for blocking synchronizers, but
     * use the same basic tactic of holding some of the control
     * information about a thread in the predecessor of its node.  A
     * "status" field in each node keeps track of whether a thread
     * should block.  A node is signalled when its predecessor
     * releases.  Each node of the queue otherwise serves as a
     * specific-notification-style monitor holding a single waiting
     * thread. The status field does NOT control whether threads are
     * granted locks etc though.  A thread may try to acquire if it is
     * first in the queue. But being first does not guarantee success;
     * it only gives the right to contend.  So the currently released
     * contender thread may need to rewait.
     *
     * <p>To enqueue into a CLH lock, you atomically splice it in as new
     * tail. To dequeue, you just set the head field.
     * <pre>
     *      +------+  prev +-----+       +-----+
     * head |      | <---- |     | <---- |     |  tail
     *      +------+       +-----+       +-----+
     * </pre>
     *
     * <p>Insertion into a CLH queue requires only a single atomic
     * operation on "tail", so there is a simple atomic point of
     * demarcation from unqueued to queued. Similarly, dequeuing
     * involves only updating the "head". However, it takes a bit
     * more work for nodes to determine who their successors are,
     * in part to deal with possible cancellation due to timeouts
     * and interrupts.
     *
     * <p>The "prev" links (not used in original CLH locks), are mainly
     * needed to handle cancellation. If a node is cancelled, its
     * successor is (normally) relinked to a non-cancelled
     * predecessor. For explanation of similar mechanics in the case
     * of spin locks, see the papers by Scott and Scherer at
     * http://www.cs.rochester.edu/u/scott/synchronization/
     *
     * <p>We also use "next" links to implement blocking mechanics.
     * The thread id for each node is kept in its own node, so a
     * predecessor signals the next node to wake up by traversing
     * next link to determine which thread it is.  Determination of
     * successor must avoid races with newly queued nodes to set
     * the "next" fields of their predecessors.  This is solved
     * when necessary by checking backwards from the atomically
     * updated "tail" when a node's successor appears to be null.
     * (Or, said differently, the next-links are an optimization
     * so that we don't usually need a backward scan.)
     *
     * <p>Cancellation introduces some conservatism to the basic
     * algorithms.  Since we must poll for cancellation of other
     * nodes, we can miss noticing whether a cancelled node is
     * ahead or behind us. This is dealt with by always unparking
     * successors upon cancellation, allowing them to stabilize on
     * a new predecessor, unless we can identify an uncancelled
     * predecessor who will carry this responsibility.
     *
     * <p>CLH queues need a dummy header node to get started. But
     * we don't create them on construction, because it would be wasted
     * effort if there is never contention. Instead, the node
     * is constructed and head and tail pointers are set upon first
     * contention.
     *
     * <p>Threads waiting on Conditions use the same nodes, but
     * use an additional link. Conditions only need to link nodes
     * in simple (non-concurrent) linked queues because they are
     * only accessed when exclusively held.  Upon await, a node is
     * inserted into a condition queue.  Upon signal, the node is
     * transferred to the main queue.  A special value of status
     * field is used to mark which queue a node is on.
     *
     * <p>Thanks go to Dave Dice, Mark Moir, Victor Luchangco, Bill
     * Scherer and Michael Scott, along with members of JSR-166
     * expert group, for helpful ideas, discussions, and critiques
     * on the design of this class.
     */
    static final class Node {
        /**
         * Marker to indicate a node is waiting in shared mode
         */
        static final Node SHARED = new Node();
        /**
         * Marker to indicate a node is waiting in exclusive mode
         */
        static final Node EXCLUSIVE = null;

        /**
         * waitStatus value to indicate thread has cancelled
         */
        static final int CANCELLED = 1;
        /**
         * waitStatus value to indicate successor's thread needs unparking
         */
        static final int SIGNAL = -1;
        /**
         * waitStatus value to indicate thread is waiting on condition
         */
        static final int CONDITION = -2;
        /**
         * waitStatus value to indicate the next acquireShared should
         * unconditionally propagate
         */
        static final int PROPAGATE = -3;

        /**
         * Status field, taking on only the values:
         * SIGNAL:     The successor of this node is (or will soon be)
         * blocked (via park), so the current node must
         * unpark its successor when it releases or
         * cancels. To avoid races, acquire methods must
         * first indicate they need a signal,
         * then retry the atomic acquire, and then,
         * on failure, block.
         * CANCELLED:  This node is cancelled due to timeout or interrupt.
         * Nodes never leave this state. In particular,
         * a thread with cancelled node never again blocks.
         * CONDITION:  This node is currently on a condition queue.
         * It will not be used as a sync queue node
         * until transferred, at which time the status
         * will be set to 0. (Use of this value here has
         * nothing to do with the other uses of the
         * field, but simplifies mechanics.)
         * PROPAGATE:  A releaseShared should be propagated to other
         * nodes. This is set (for head node only) in
         * doReleaseShared to ensure propagation
         * continues, even if other operations have
         * since intervened.
         * 0:          None of the above
         * <p>
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         * <p>
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         */
        volatile int waitStatus;

        /**
         * Link to predecessor node that current node/thread relies on
         * for checking waitStatus. Assigned during enqueuing, and nulled
         * out (for sake of GC) only upon dequeuing.  Also, upon
         * cancellation of a predecessor, we short-circuit while
         * finding a non-cancelled one, which will always exist
         * because the head node is never cancelled: A node becomes
         * head only as a result of successful acquire. A
         * cancelled thread never succeeds in acquiring, and a thread only
         * cancels itself, not any other node.
         */
        volatile Node prev;

        /**
         * Link to the successor node that the current node/thread
         * unparks upon release. Assigned during enqueuing, adjusted
         * when bypassing cancelled predecessors, and nulled out (for
         * sake of GC) when dequeued.  The enq operation does not
         * assign next field of a predecessor until after attachment,
         * so seeing a null next field does not necessarily mean that
         * node is at end of queue. However, if a next field appears
         * to be null, we can scan prev's from the tail to
         * double-check.  The next field of cancelled nodes is set to
         * point to the node itself instead of null, to make life
         * easier for isOnSyncQueue.
         */
        volatile Node next;

        /**
         * The thread that enqueued this node.  Initialized on
         * construction and nulled out after use.
         */
        volatile Thread thread;

        /**
         * Link to next node waiting on condition, or the special
         * value SHARED.  Because condition queues are accessed only
         * when holding in exclusive mode, we just need a simple
         * linked queue to hold nodes while they are waiting on
         * conditions. They are then transferred to the queue to
         * re-acquire. And because conditions can only be exclusive,
         * we save a field by using special value to indicate shared
         * mode.
         */
        Node nextWaiter;

        /**
         * Returns true if node is waiting in shared mode.
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * Returns previous node, or throws NullPointerException if null.
         * Use when predecessor cannot be null.  The null check could
         * be elided, but is present to help the VM.
         *
         * @return the predecessor of this node
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    private volatile int state;

    /**
     * Returns the current value of synchronization state.
     * This operation has memory semantics of a {@code volatile} read.
     *
     * @return current state value
     */
    protected final int getState() {
        return state;
    }

    /**
     * Sets the value of synchronization state.
     * This operation has memory semantics of a {@code volatile} write.
     *
     * @param newState the new state value
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * Atomically sets synchronization state to the given updated
     * value if the current state value equals the expected value.
     * This operation has memory semantics of a {@code volatile} read
     * and write.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that the actual
     * value was not equal to the expected value.
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices
     * to improve responsiveness with very short timeouts.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * Inserts node into queue, initializing if necessary. See picture above.
     *
     * @param node the node to insert
     * @return node's predecessor
     */
    //就是通过自旋操作把当前节点加入到队列中
    private Node enq(final Node node) {
        //自旋锁
        for (;;) {
            //tail默认就是null
            Node t = tail;
            if (t == null) { // Must initialize
                //因为tail默认是null，所以首次一定会进来
                //compareAndSetHead在下面
                //也就是首次一定会把一个新的node设置为head
                if (compareAndSetHead(new Node()))
                    //tail=head=new Node()
                    tail = head;
                //到这里就是tail和head都会指向new Node
            } else {
                //第二次一定进入else发
                //假如此时进入了一个线程A，这个A已经被封装成了node传进来
                //当前的node的pre指向t，也就是tail，也就是刚才创建的Node，
                //因为第一行就定义了Node t = tail，而t=head=node
                node.prev = t;
                //这里看下面的compareAndSetTail方法
                //把tail节点赋值为新传入的node（Thread A），赋值操作就相当于指向
                if (compareAndSetTail(t, node)) {
                    //这里的t指的是原来的tail节点，tail指向一开始的new Node
                    //所以就是new Node的next指向新传入的node（Thread A）
                    t.next = node;
                    return t;
                }
            }
        }
    }

    // private Node enq(final Node node) {
    //     for (; ; ) {
    //         Node t = tail;
    //         if (t == null) { // Must initialize
    //             if (compareAndSetHead(new Node()))
    //                 tail = head;
    //         } else {
    //             node.prev = t;
    //             if (compareAndSetTail(t, node)) {
    //                 t.next = node;
    //                 return t;
    //             }
    //         }
    //     }
    // }

    /**
     * Creates and enqueues node for current thread and given mode.
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    //当 tryAcquire 方法获取锁失败以后，则会先调用 addWaiter 将当前线程封装成Node.
    //入参 mode 表示当前节点的状态，传递的参数是 Node.EXCLUSIVE，表示独占状态。意味着重入锁用到了 AQS 的独占锁功能

    // 1. 将当前线程封装成 Node
    // 2. 当前链表中的 tail 节点是否为空，如果不为空，则通过 cas 操作把当前线程的node 添加到 AQS 队列
    // 3. 如果为空或者 cas 失败，调用 enq 将节点添加到 AQS 队列
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);//把当前线程封装为 Node
        // Try the fast path of enq; backup to full enq on failure
        Node pred = tail;//tail 是 AQS 中表示同比队列队尾的属性，默认是 null
        if (pred != null) {//tail 不为空的情况下，说明队列中存在节点node.prev = pred;
            node.prev = pred;
            //把当前线程的 Node 的 prev 指向 tail
            if (compareAndSetTail(pred, node)) {//通过 cas 把 node加入到 AQS 队列，也就是设置为 tail
                pred.next = node;//设置成功以后，把原 tail 节点的 next指向当前
                return node;//tail=null,把 node 添加到同步队列return node;
            }
        }
        enq(node);
        return node;
    }

    /**
     * Sets head of queue to be node, thus dequeuing. Called only by
     * acquire methods.  Also nulls out unused fields for sake of GC
     * and to suppress unnecessary signals and traversals.
     *
     * @param node the node
     */
    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     */

    private void unparkSuccessor(Node node) {
        int ws = node.waitStatus;//获得 head 节点的状态
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);// 设置 head 节点状态为 0
        Node s = node.next;//得到 head 节点的下一个节点
        if (s == null || s.waitStatus > 0) {
            // 为什么在释放锁的时候是从 tail 进行扫描

            //如果下一个节点为 null 或者 status>0 表示 cancelled 状态.
            //通过从尾部节点开始扫描，找到距离 head 最近的一个 waitStatus<=0 的节点

            //s == null 下一个节点为空，可能往里面放节点的时候next操作没做完呢，关系还没有建立完整，这算是一个真正的节点吗？
            // 只是没有next而已，设置了pre，设置了tail  next但是已经有了tail，只差next了却因为没有next无法往下遍历但是从后往前是没
            // 有这种情况的因为一定有了pre这里两种有next直接唤醒没有从尾部

            //具体的看pdf
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    //通过赋值的方式把取消的节点移除掉。
                    s = t;
        }
        if (s != null) //next 节点不为空，直接唤醒这个线程即可
            LockSupport.unpark(s.thread);
    }

    // private void unparkSuccessor(Node node) {
    //     /*
    //      * If status is negative (i.e., possibly needing signal) try
    //      * to clear in anticipation of signalling.  It is OK if this
    //      * fails or if status is changed by waiting thread.
    //      */
    //     int ws = node.waitStatus;//获得 head 节点的状态
    //     if (ws < 0)
    //         compareAndSetWaitStatus(node, ws, 0);// 设置 head 节点状态为 0
    //
    //     /*
    //      * Thread to unpark is held in successor, which is normally
    //      * just the next node.  But if cancelled or apparently null,
    //      * traverse backwards from tail to find the actual
    //      * non-cancelled successor.
    //      */
    //     Node s = node.next;
    //     if (s == null || s.waitStatus > 0) {
    //         s = null;
    //         for (Node t = tail; t != null && t != node; t = t.prev)
    //             if (t.waitStatus <= 0)
    //                 s = t;
    //     }
    //     if (s != null)
    //         LockSupport.unpark(s.thread);
    // }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
    //共享锁的释放和独占锁的释放有一定的差别
    // 前面唤醒锁的逻辑和独占锁是一样，先判断头结点是不是
    // SIGNAL 状态，如果是，则修改为 0，并且唤醒头结点的
    // 下一个节点PROPAGATE： 标识为 PROPAGATE 状态的节
    // 点，是共享锁模式下的节点状态，处于这个状态
    // 下的节点，会对线程的唤醒进行传播

    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (; ; ) {
            Node h = head;
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                //如果是signal，unpark
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    //unpark head的下一个节点，但是理论上是唤醒所有的节点
                    unparkSuccessor(h);
                    // 这个 CAS 失败的场景是：执行到这里的时候(&&后面)，刚好有一个节点入队，添加到此节点后面，
                    // 入队会将这个 ws 设置为 -1cas比得时候不是0，所以失败了。
                } else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            // 如果到这里的时候，前面唤醒的线程已经占领了 head，那么再循环
            // 通过检查头节点是否改变了，如果改变了就继续循环

            // 如果h还是指向头结点，说明前面这段代码执行过程中没有其他线程对头结点进行过处理
            //已经不阻塞了。
            if (h == head)    //没有走上面的 if (ws == Node.SIGNAL) 逻辑 ,没有线程被阻塞// loop if head changed
                break;
            //  h == head：说明头节点还没有被刚刚用unparkSuccessor 唤醒的线程（这里可以理解为
            // ThreadB）占有，此时 break 退出循环。

            // h != head：头节点被刚刚唤醒的线程（这里可以理解为ThreadB）占有，那么这里重新进入下一轮循环，唤醒下
            // 一个节点（这里是 ThreadB ）。我们知道，等到ThreadB 被唤醒后，其实是会主动唤醒 ThreadC...
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * @param node      the node
     * @param propagate the return value from a tryAcquireShared
     */
    //这个方法的主要作用是把被唤醒的节点，设置成 head 节
    // 点。 然后继续唤醒队列中的其他线程。
    // 由于现在队列中有 3 个线程处于阻塞状态，一旦 ThreadA
    // 被唤醒，并且设置为 head 之后，会继续唤醒后续的
    // ThreadB
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
                (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            //是传播状态。
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    // Utilities for various versions of acquire

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    //取消获得锁的操作
    //说明已经获得了锁
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;
        //1. node不再关联到任何线程
        node.thread = null;
        //2. 跳过被cancel的前继node，找到一个有效的前继节点pred
        // Skip cancelled predecessors
        Node pred = node.prev;
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        Node predNext = pred.next;
        //3. 将node的waitStatus置为CANCELLED
        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        node.waitStatus = Node.CANCELLED;
        //4. 如果node是tail，更新tail为pred，并使pred.next指向null
        // If we are the tail, remove ourselves.
        if (node == tail && compareAndSetTail(node, pred)) {
            compareAndSetNext(pred, predNext, null);
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            //
            int ws;
            //5. 如果node既不是tail，又不是head的后继节点
            //则将node的前继节点的waitStatus置为SIGNAL
            //并使pre的前继节点指向node的后继节点
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
                //6. 如果node是head的后继节点，则直接唤醒node的后继节点
                //为什么唤醒后置节点？哪来的后置节点？当前争夺的不就是最后一个？wait一个一个往后面添加的啊
                //计算机是多线程的，这里从添加到尾结点到争夺的过程中，又有别人加到了尾结点的话。

                //为什么这里唤醒下一个节点？
                //被cancel的node是head的后继节点，是队列中唯一一个有资格去尝试获取资源的节点。
                // 他将资格放弃了，自然有义务去唤醒他的后继来接棒。
                //自己不行，就让别人来获取，要不前面这么多逻辑都白跑了
                unparkSuccessor(node);
            }

            node.next = node; // help GC
        //    为什么可以gc？
        //     将node.next指向自身。避免node.next是指向别的对象的。减少了对象之间可能的相互引用。
        //     我大概也就知道这么点意思。
        }
    }
    // private void cancelAcquire(Node node) {
    //     // Ignore if node doesn't exist
    //     if (node == null)
    //         return;
    //     //node中的thread去掉
    //     node.thread = null;
    //
    //     // Skip cancelled predecessors
    //     Node pred = node.prev;
    //     while (pred.waitStatus > 0)
    //         node.prev = pred = pred.prev;
    //
    //     // predNext is the apparent node to unsplice. CASes below will
    //     // fail if not, in which case, we lost race vs another cancel
    //     // or signal, so no further action is necessary.
    //     Node predNext = pred.next;
    //
    //     // Can use unconditional write instead of CAS here.
    //     // After this atomic step, other Nodes can skip past us.
    //     // Before, we are free of interference from other threads.
    //     //前一个加点设置成取消节点
    //     node.waitStatus = Node.CANCELLED;
    //
    //     // If we are the tail, remove ourselves.
    //     //如果当前节点是尾结点->设置尾结点为前置节点
    //     if (node == tail && compareAndSetTail(node, pred)) {
    //         // 把后置节点设置为null
    //         compareAndSetNext(pred, predNext, null);
    //     } else {
    //         // If successor needs signal, try to set pred's next-link
    //         // so it will get one. Otherwise wake it up to propagate.
    //         int ws;
    //         if (pred != head &&
    //                 ((ws = pred.waitStatus) == Node.SIGNAL ||
    //                         (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
    //                 pred.thread != null) {
    //             Node next = node.next;
    //             if (next != null && next.waitStatus <= 0)
    //                 compareAndSetNext(pred, predNext, next);
    //         } else {
    //             unparkSuccessor(node);
    //         }
    //
    //         node.next = node; // help GC
    //     }
    // }

    /**
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */

    //如果 ThreadA 的锁还没有释放的情况下， ThreadB 和 ThreadC 来争抢锁肯定是会失败，
    // 那么失败以后会调用 shouldParkAfterFailedAcquire 方法

    //Node 有 5 中状态，分别是： CANCELLED（1） ， SIGNAL（-1） 、 CONDITION（-
    // 2） 、 PROPAGATE(-3)、默认状态(0)
    // CANCELLED: 在同步队列中等待的线程等待超时或被中断，需要从同步队列中取消该 Node 的结点, 其结点的 waitStatus 为 CANCELLED，即结束状态，进入该状态后的结点将不会再变化
    // SIGNAL: 只要前置节点释放锁，就会通知标识为 SIGNAL 状态的后续节点的线程
    // CONDITION： 和 Condition 有关系，后续会讲解
    // PROPAGATE： 共享模式下， PROPAGATE 状态的线程处于可运行状态
    // 0:初始状态

    // 这个方法的主要作用是，通过 Node 的状态来判断， ThreadA 竞争锁失败以后是否应该被挂起。

    // 1. 如果 ThreadA 的 pred 节点状态为 SIGNAL，那就表示可以放心挂起当前线程
    // 2. 通过循环扫描链表把 CANCELLED 状态的节点移除
    // 3. 修改 pred 节点的状态为 SIGNAL,返回 false.返回 false 时，也就是不需要挂起，
    // 返回 true 则需要调用 parkAndCheckInterrupt挂起当前线程

    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;//前置节点的 waitStatus
        if (ws == Node.SIGNAL)//如果前置节点为 SIGNAL， 意味着只需要等待其他前置节点的线程被释放，
            return true;//返回 true，意味着可以直接放心的挂起了
        //就是说前面的节点是signal的就挂起，这里的挂起，下面进行了赋值，每次有新的进来，
        //前面的都是signal也就是说，从第一个空节点，到最后一个几点，都会挂起。
        if (ws > 0) {//ws 大于 0，意味着 prev 节点取消了排队，直接移除这个节点就行
            do {
                //把node的前面指向，原来的前面的前面。
                node.prev = pred = pred.prev;
                //相当于: pred=pred.prev;
                node.prev=pred;
            } while (pred.waitStatus > 0); //这里采用循环，从双向列表中移除 CANCELLED 的节点
            pred.next = node;
        } else {
            //说明标志位还没有被设置过
            //利用 cas 设置 prev 节点的状态为 SIGNAL(-1）
            /*
            * waitStatus must be 0 or PROPAGATE.  Indicate that we
            * need a signal, but don't park yet.  Caller will need to
            * retry to make sure it cannot acquire before parking.
            */
            compareAndSetWaitStatus(pred, ws,Node.SIGNAL);
        }
        return false;
    }
    // private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    //     int ws = pred.waitStatus;
    //     if (ws == Node.SIGNAL)
    //         /*
    //          * This node has already set status asking a release
    //          * to signal it, so it can safely park.
    //          */
    //         return true;
    //     if (ws > 0) {
    //         /*
    //          * Predecessor was cancelled. Skip over predecessors and
    //          * indicate retry.
    //          */
    //         do {
    //             node.prev = pred = pred.prev;
    //         } while (pred.waitStatus > 0);
    //         pred.next = node;
    //     } else {
    //         /*
    //          * waitStatus must be 0 or PROPAGATE.  Indicate that we
    //          * need a signal, but don't park yet.  Caller will need to
    //          * retry to make sure it cannot acquire before parking.
    //          */
    //         compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
    //     }
    //     return false;
    // }

    /**
     * Convenience method to interrupt current thread.
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * Convenience method to park and then check if interrupted
     *
     * @return {@code true} if interrupted
     */

    //使用 LockSupport.park 挂起当前线程编程 WATING 状态
    // Thread.interrupted，返回当前线程是否被其他线程触发过中断请求，也就是thread.interrupt();
    // 如果有触发过中断请求，那么这个方法会返回当前的中断标识true，并且对中断标识进行复位标识已经响应过了中断请求。
    // 如果返回 true，意味着在 acquire 方法中会执行 selfInterrupt()。
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        //阻塞争抢锁的时候，其他线程发起中断请求，这里是无法响应的
        //所以当下次被唤醒的时候再去响应中断请求
        //interrupt会中断park，所以这里给他复位，留到以后处理，不希望在阻塞争抢锁的时候通过中断唤醒。

        //所以返回中断状态，并且复位
        return Thread.interrupted();
        //返回是否触发过中断请求
    }

    /*
     * Various flavors of acquire, varying in exclusive/shared and
     * control modes.  Each is mostly the same, but annoyingly
     * different.  Only a little bit of factoring is possible due to
     * interactions of exception mechanics (including ensuring that we
     * cancel if tryAcquire throws exception) and other control, at
     * least not without hurting performance too much.
     */

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg  the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    // 通过 addWaiter 方法把线程添加到链表后， 会接着把 Node 作为参数传递给acquireQueued 方法，去竞争锁
    // 1. 获取当前节点的 prev 节点
    // 2. 如果 prev 节点为 head 节点，那么它就有资格去争抢锁，调用 tryAcquire 抢占锁
    // 3. 抢占锁成功以后，把获得锁的节点设置为 head，并且移除原来的初始化 head节点
    // 4. 如果获得锁失败，则根据 waitStatus 决定是否需要挂起线程
    // 5. 最后，通过 cancelAcquire 取消获得锁的操作
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();//获取当前节点的 prev 节点
                //那边release唤醒之后
                //1. 把 ThreadB 节点当成 head
                // 2. 把原 head 节点的 next 节点指向为 null
                if (p == head && tryAcquire(arg)) {//如果是 head 节点，说明有资格去争抢锁
                    setHead(node);//获取锁成功，也就是ThreadA 已经释放了锁，然后设置 head 为 ThreadB 获得执行权限
                    p.next = null;//把原 head 节点从链表中移除
                    failed = false;
                    return interrupted;
                } //ThreadA 可能还没释放锁，使得 ThreadB 在执行 tryAcquire 时会返回 false
                //如果 ThreadA 的锁还没有释放的情况下， ThreadB 和 ThreadC 来争抢锁肯定是会失败，
                // 那么失败以后会调用 shouldParkAfterFailedAcquire 方法
                if (shouldParkAfterFailedAcquire(p,
                        node) &&
                        parkAndCheckInterrupt())
                    interrupted = true; //并且返回当前线程在等待过程中有没有中断过。
            }
        } finally {
            if (failed)
                //一旦发生异常，导致获取锁失败，也没有获得锁，也没有挂起，会调用这里
                //说明该设置为取消状态，且出队列了。
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive interruptible mode.
     *
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg          the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared uninterruptible mode.
     *
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared interruptible mode.
     *
     * @param arg the acquire argument
     */
    //1. addWaiter 设置为 shared 模式。
    // 2. tryAcquire 和 tryAcquireShared 的返回值不同，因此会多出一个判断过程
    // 3. 在 判 断 前 驱 节 点 是 头 节 点 后 ， 调 用 了
    // setHeadAndPropagate 方法，而不是简单的更新一下头节点
    private void doAcquireSharedInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);//创建一个共享模式的节点添加到队列中（AQS队列）
        boolean failed = true;
        try {
            for (; ; ) {//被唤醒的线程进入下一次循环继续判断
                //拿到前置节点
                final Node p = node.predecessor();
                //前置节点是head
                if (p == head) {
                    int r = tryAcquireShared(arg);//就判断尝试获取锁，第一次肯定是-1
                    //一旦 ThreadA 被唤醒，代码又会继续回到
                    // doAcquireSharedInterruptibly 中来执行。 如果当前 state
                    // 满足=0 的条件，则会执行 setHeadAndPropagate 方法
                    if (r >= 0) {//r>=0 表示获取到了执行权限，这个时候因为 state!=0，所以不会执行这段代码
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC  //把当前节点移除 aqs 队列
                        failed = false;
                        return;
                    }
                }
                //阻塞线程
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Acquires in shared timed mode.
     *
     * @param arg          the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (; ; ) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *            passed to an acquire method, or is the value saved on entry
     *            to a condition wait.  The value is otherwise uninterpreted
     *            and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     * been acquired.
     * @throws IllegalMonitorStateException  if acquiring would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *            passed to a release method, or the current state value upon
     *            entry to a condition wait.  The value is otherwise
     *            uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     * state, so that any waiting threads may attempt to acquire;
     * and {@code false} otherwise.
     * @throws IllegalMonitorStateException  if releasing would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *            passed to an acquire method, or is the value saved on entry
     *            to a condition wait.  The value is otherwise uninterpreted
     *            and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     * mode succeeded but no subsequent shared-mode acquire can
     * succeed; and a positive value if acquisition in shared
     * mode succeeded and subsequent shared-mode acquires might
     * also succeed, in which case a subsequent waiting thread
     * must check availability. (Support for three different
     * return values enables this method to be used in contexts
     * where acquires only sometimes act exclusively.)  Upon
     * success, this object has been acquired.
     * @throws IllegalMonitorStateException  if acquiring would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *            passed to a release method, or the current state value upon
     *            entry to a condition wait.  The value is otherwise
     *            uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     * waiting acquire (shared or exclusive) to succeed; and
     * {@code false} otherwise
     * @throws IllegalMonitorStateException  if releasing would place this
     *                                       synchronizer in an illegal state. This exception must be
     *                                       thrown in a consistent fashion for synchronization to work
     *                                       correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     * {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *            {@link #tryAcquire} but is otherwise uninterpreted and
     *            can represent anything you like.
     */

    //这里说明一下“1”的含义，它是设置“锁的状态”的参数。对于“独占锁”而言，锁处于可获取状态时，它的状态值是0；
    // 锁被线程初次获取到了，它的状态值就变成了1。
    // 由于ReentrantLock(公平锁/非公平锁)是可重入锁，所以“独占锁”可以被单个线程多次获取，每获取1次就将锁的状态+1。
    // 也就是说，初次获取锁时，通过acquire(1)将锁的状态值设为1；再次获取锁时，将锁的状态值设为2；
    // 依次类推...这就是为什么获取锁时，传入的参数是1的原因了。
    // 可重入就是指锁可以被单个线程多次获取。
    // https://blog.csdn.net/wangxiaotongfan/article/details/51800981?utm_source=blogxgwz7

    //1. 通过 tryAcquire 尝试获取独占锁，如果成功返回 true，失败返回 false
    // 2. 如果 tryAcquire 失败，则会通过 addWaiter 方法将当前线程封装成 Node 添加
    // 到 AQS 队列尾部
    // 3. acquireQueued，将 Node 作为参数，通过自旋去尝试获取锁。
    public final void acquire(int arg) {
        if (!tryAcquire(arg) &&
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    /**
     * Acquires in exclusive mode, aborting if interrupted.
     * Implemented by first checking interrupt status, then invoking
     * at least once {@link #tryAcquire}, returning on
     * success.  Otherwise the thread is queued, possibly repeatedly
     * blocking and unblocking, invoking {@link #tryAcquire}
     * until success or the thread is interrupted.  This method can be
     * used to implement method {@link Lock#lockInterruptibly}.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *            {@link #tryAcquire} but is otherwise uninterpreted and
     *            can represent anything you like.
     * @throws InterruptedException if the current thread is interrupted
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    /**
     * Attempts to acquire in exclusive mode, aborting if interrupted,
     * and failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquire}, returning on success.  Otherwise, the thread is
     * queued, possibly repeatedly blocking and unblocking, invoking
     * {@link #tryAcquire} until success or the thread is interrupted
     * or the timeout elapses.  This method can be used to implement
     * method {@link Lock#tryLock(long, TimeUnit)}.
     *
     * @param arg          the acquire argument.  This value is conveyed to
     *                     {@link #tryAcquire} but is otherwise uninterpreted and
     *                     can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquire(arg) ||
                doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     *
     * @param arg the release argument.  This value is conveyed to
     *            {@link #tryRelease} but is otherwise uninterpreted and
     *            can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */

    public final boolean release(int arg) {
        if (tryRelease(arg)) {//释放锁成功
            Node h = head;//得到 aqs 中 head 节点
            if (h != null && h.waitStatus != 0)//如果 head 节点不为空并且状态！ =0.调用 unparkSuccessor(h)唤醒后续节点
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * Acquires in shared mode, ignoring interrupts.  Implemented by
     * first invoking at least once {@link #tryAcquireShared},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquireShared} until success.
     *
     * @param arg the acquire argument.  This value is conveyed to
     *            {@link #tryAcquireShared} but is otherwise uninterpreted
     *            and can represent anything you like.
     */
    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)
            doAcquireShared(arg);
    }

    /**
     * Acquires in shared mode, aborting if interrupted.  Implemented
     * by first checking interrupt status, then invoking at least once
     * {@link #tryAcquireShared}, returning on success.  Otherwise the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted.
     *
     * @param arg the acquire argument.
     *            This value is conveyed to {@link #tryAcquireShared} but is
     *            otherwise uninterpreted and can represent anything
     *            you like.
     * @throws InterruptedException if the current thread is interrupted
     */

    //countdownlatch 也用到了 AQS，在 CountDownLatch 内
    // 部写了一个 Sync 并且继承了 AQS 这个抽象类重写了 AQS
    // 中的共享锁方法。首先看到下面这个代码，这块代码主要
    // 是 判 断 当 前 线 程 是 否 获 取 到 了 共 享 锁 ; （ 在
    // CountDownLatch 中 ， 使 用 的 是 共 享 锁 机 制 ， 因 为
    // CountDownLatch 并不需要实现互斥的特性）

    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        if (tryAcquireShared(arg) < 0)//state 如果不等于 0，说明当前线程需要加入到共享锁队列中
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * Attempts to acquire in shared mode, aborting if interrupted, and
     * failing if the given timeout elapses.  Implemented by first
     * checking interrupt status, then invoking at least once {@link
     * #tryAcquireShared}, returning on success.  Otherwise, the
     * thread is queued, possibly repeatedly blocking and unblocking,
     * invoking {@link #tryAcquireShared} until success or the thread
     * is interrupted or the timeout elapses.
     *
     * @param arg          the acquire argument.  This value is conveyed to
     *                     {@link #tryAcquireShared} but is otherwise uninterpreted
     *                     and can represent anything you like.
     * @param nanosTimeout the maximum number of nanoseconds to wait
     * @return {@code true} if acquired; {@code false} if timed out
     * @throws InterruptedException if the current thread is interrupted
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        return tryAcquireShared(arg) >= 0 ||
                doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * Releases in shared mode.  Implemented by unblocking one or more
     * threads if {@link #tryReleaseShared} returns true.
     *
     * @param arg the release argument.  This value is conveyed to
     *            {@link #tryReleaseShared} but is otherwise uninterpreted
     *            and can represent anything you like.
     * @return the value returned from {@link #tryReleaseShared}
     */
    //由于线程被 await 方法阻塞了，所以只有等到
    // countdown 方法使得 state=0 的时候才会被唤醒，我们
    // 来看看 countdown 做了什么
    // 1. 只有当 state 减为 0 的时候， tryReleaseShared 才返
    // 回 true, 否则只是简单的 state = state - 1
    // 2. 如果 state=0, 则调用 doReleaseShared
    // 唤醒处于 await 状态下的线程
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            doReleaseShared();
            return true;
        }
        return false;
    }

    // Queue inspection methods

    /**
     * Queries whether any threads are waiting to acquire. Note that
     * because cancellations due to interrupts and timeouts may occur
     * at any time, a {@code true} return does not guarantee that any
     * other thread will ever acquire.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there may be other threads waiting to acquire
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * Queries whether any threads have ever contended to acquire this
     * synchronizer; that is if an acquire method has ever blocked.
     *
     * <p>In this implementation, this operation returns in
     * constant time.
     *
     * @return {@code true} if there has ever been contention
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * Returns the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued.
     *
     * <p>In this implementation, this operation normally returns in
     * constant time, but may iterate upon contention if other threads are
     * concurrently modifying the queue.
     *
     * @return the first (longest-waiting) thread in the queue, or
     * {@code null} if no threads are currently queued
     */
    public final Thread getFirstQueuedThread() {
        // handle only fast path, else relay
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * Version of getFirstQueuedThread called when fastpath fails
     */
    private Thread fullGetFirstQueuedThread() {
        /*
         * The first node is normally head.next. Try to get its
         * thread field, ensuring consistent reads: If thread
         * field is nulled out or s.prev is no longer head, then
         * some other thread(s) concurrently performed setHead in
         * between some of our reads. We try this twice before
         * resorting to traversal.
         */
        Node h, s;
        Thread st;
        if (((h = head) != null && (s = h.next) != null &&
                s.prev == head && (st = s.thread) != null) ||
                ((h = head) != null && (s = h.next) != null &&
                        s.prev == head && (st = s.thread) != null))
            return st;

        /*
         * Head's next field might not have been set yet, or may have
         * been unset after setHead. So we must check to see if tail
         * is actually first node. If not, we continue on, safely
         * traversing from tail back to head to find first,
         * guaranteeing termination.
         */

        Node t = tail;
        Thread firstThread = null;
        while (t != null && t != head) {
            Thread tt = t.thread;
            if (tt != null)
                firstThread = tt;
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * Returns true if the given thread is currently queued.
     *
     * <p>This implementation traverses the queue to determine
     * presence of the given thread.
     *
     * @param thread the thread
     * @return {@code true} if the given thread is on the queue
     * @throws NullPointerException if the thread is null
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * Returns {@code true} if the apparent first queued thread, if one
     * exists, is waiting in exclusive mode.  If this method returns
     * {@code true}, and the current thread is attempting to acquire in
     * shared mode (that is, this method is invoked from {@link
     * #tryAcquireShared}) then it is guaranteed that the current thread
     * is not the first queued thread.  Used only as a heuristic in
     * ReentrantReadWriteLock.
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        return (h = head) != null &&
                (s = h.next) != null &&
                !s.isShared() &&
                s.thread != null;
    }

    /**
     * Queries whether any threads have been waiting to acquire longer
     * than the current thread.
     *
     * <p>An invocation of this method is equivalent to (but may be
     * more efficient than):
     * <pre> {@code
     * getFirstQueuedThread() != Thread.currentThread() &&
     * hasQueuedThreads()}</pre>
     *
     * <p>Note that because cancellations due to interrupts and
     * timeouts may occur at any time, a {@code true} return does not
     * guarantee that some other thread will acquire before the current
     * thread.  Likewise, it is possible for another thread to win a
     * race to enqueue after this method has returned {@code false},
     * due to the queue being empty.
     *
     * <p>This method is designed to be used by a fair synchronizer to
     * avoid <a href="AbstractQueuedSynchronizer#barging">barging</a>.
     * Such a synchronizer's {@link #tryAcquire} method should return
     * {@code false}, and its {@link #tryAcquireShared} method should
     * return a negative value, if this method returns {@code true}
     * (unless this is a reentrant acquire).  For example, the {@code
     * tryAcquire} method for a fair, reentrant, exclusive mode
     * synchronizer might look like this:
     *
     * <pre> {@code
     * protected boolean tryAcquire(int arg) {
     *   if (isHeldExclusively()) {
     *     // A reentrant acquire; increment hold count
     *     return true;
     *   } else if (hasQueuedPredecessors()) {
     *     return false;
     *   } else {
     *     // try to acquire normally
     *   }
     * }}</pre>
     *
     * @return {@code true} if there is a queued thread preceding the
     * current thread, and {@code false} if the current thread
     * is at the head of the queue or the queue is empty
     * @since 1.7
     */
    public final boolean hasQueuedPredecessors() {
        // The correctness of this depends on head being initialized
        // before tail and on head.next being accurate if the current
        // thread is first in queue.
        Node t = tail; // Read fields in reverse initialization order
        Node h = head;
        Node s;
        return h != t &&
                ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    // Instrumentation and monitoring methods

    /**
     * Returns an estimate of the number of threads waiting to
     * acquire.  The value is only an estimate because the number of
     * threads may change dynamically while this method traverses
     * internal data structures.  This method is designed for use in
     * monitoring system state, not for synchronization
     * control.
     *
     * @return the estimated number of threads waiting to acquire
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate.  The elements of the
     * returned collection are in no particular order.  This method is
     * designed to facilitate construction of subclasses that provide
     * more extensive monitoring facilities.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in exclusive mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to an exclusive acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a collection containing threads that may be waiting to
     * acquire in shared mode. This has the same properties
     * as {@link #getQueuedThreads} except that it only returns
     * those threads waiting due to a shared acquire.
     *
     * @return the collection of threads
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * Returns a string identifying this synchronizer, as well as its state.
     * The state, in brackets, includes the String {@code "State ="}
     * followed by the current value of {@link #getState}, and either
     * {@code "nonempty"} or {@code "empty"} depending on whether the
     * queue is empty.
     *
     * @return a string identifying this synchronizer, as well as its state
     */
    public String toString() {
        int s = getState();
        String q = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }


    // Internal support methods for Conditions

    /**
     * Returns true if a node, always one that was initially placed on
     * a condition queue, is now waiting to reacquire on sync queue.
     *
     * @param node the node
     * @return true if is reacquiring
     */
    //判断当前节点是否在同步队列中， 返回 false 表示不在，返回 true 表示在
    // 如果不在 AQS 同步队列，说明当前节点没有唤醒去争抢同步锁，所以需要把当前线程阻塞起来，直到其他的线程调用 signal 唤醒
    // 如果在 AQS 同步队列，意味着它需要去竞争同步锁去获得执行程序执行权限为什么要做这个判断呢？原因是在 condition 队列中的节
    // 点会重新加入到 AQS 队列去竞争锁。 也就是当调用 signal的时候，会把当前节点从 condition 队列转移到 AQS 队列
    // ➢ 大家思考一下，基于现在的逻辑结构。如何去判断ThreadA 这个节点是否存在于 AQS 队列中呢？
    // 1. 如果 ThreadA 的 waitStatus 的状态为 CONDITION，说明它存在于 condition 队列中，
    // 不在 AQS 队列。因为AQS 队列的状态一定不可能有 CONDITION
    // 2. 如果 node.prev 为空，说明也不存在于 AQS 队列，原因是 prev=null 在 AQS 队列中只有一种可能性，
    // 就是它是head 节点， head 节点意味着它是获得锁的节点。
    // 3. 如果 node.next 不等于空，说明一定存在于 AQS 队列中，因为只有 AQS 队列才会存在 next 和 prev 的关系
    // 4. findNodeFromTail，表示从 tail 节点往前扫描 AQS 队列，一旦发现 AQS 队列的节点和当前节点相等，说明节点一
    // 定存在于 AQS 队列中
    final boolean isOnSyncQueue(Node node) {
        // 如果 ThreadA 的 waitStatus 的状态为 CONDITION，说明它存在于 condition 队列中，不在 AQS 队列。因为AQS 队列的状态一定不可能有 CONDITION
        //如果 node.prev 为空，说明也不存在于 AQS 队列，原因是 prev=null 在 AQS 队列中只有一种可能性，就是它是head 节点
        // head 节点意味着它是获得锁的节点。
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        //如果 node.next 不等于空，说明一定存在于 AQS 队列中，因为只有 AQS 队列才会存在 next 和 prev 的关系
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        //findNodeFromTail，表示从 tail 节点往前扫描 AQS 队列，一旦发现 AQS 队列的节点和当前节点相等，说明节点一
        // 定存在于 AQS 队列中
        return findNodeFromTail(node);
    }
    //不在aqs队列中就阻塞
    /**
     * Returns true if node is on sync queue by searching backwards from tail.
     * Called only when needed by isOnSyncQueue.
     *
     * @return true if present
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (; ; ) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * Transfers a node from a condition queue onto sync queue.
     * Returns true if successful.
     *
     * @param node the node
     * @return true if successfully transferred (else the node was
     * cancelled before signal)
     */
    //该方法先是 CAS 修改了节点状态，如果成功，就将这个节点放到 AQS 队列中，然后唤醒这个节点上的线程（如果取消状态的话）。此时，
    // 那个节点就会在 await 方法中苏醒
    final boolean transferForSignal(Node node) {
        /*
         * If cannot change waitStatus, the node has been cancelled.
         */
        //更新节点的状态为 0，如果更新失败，只有一种可能就是节点被 CANCELLED 了
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        /*
         * Splice onto queue and try to set waitStatus of predecessor to
         * indicate that thread is (probably) waiting. If cancelled or
         * attempt to set waitStatus fails, wake up to resync (in which
         * case the waitStatus can be transiently and harmlessly wrong).
         */

        //调用 enq，把当前节点添加到AQS 队列。并且返回返回按当前节点的上一个节点，也就是原tail 节点
        Node p = enq(node);
        int ws = p.waitStatus;
        // 如果上一个节点的状态被取消了, 或者尝试设置上一个节点的状态为 SIGNAL 失败了(SIGNAL 表示: 他的 next
        // 节点需要停止阻塞),
        //什么时候设置signal会失败？ cancelled的时候，已经抛出异常了，一定不能再去争抢锁了，会被移除队列。

        // 在从condition队列到同步队列之前，tail节点被取消了
        // 在从condition队列到同步队列之后，tail节点没被取消，执行完ws > 0 之后，这段时间，被取消了？
        // 就通过!compareAndSetWaitStatus(p, ws, Node.SIGNAL)再判断一下
        // 这个时候偷偷的提前执行一波，就算不提前执行，AQS队列会不断的把取消节点取消掉，最终还是被unpark的。
        // 总的意思就是为了提升性能，没有这里也是合理的。
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);// 唤醒节点上的线程
        return true;//如果 node 的 prev 节点已经是signal 状态，那么被阻塞的 ThreadA 的唤醒工作由 AQS 队列来完成
    }
    //执行完 doSignal 以后，会把 condition 队列中的节点转移到 aqs 队列上
    /**
     * Transfers node, if necessary, to sync queue after a cancelled wait.
     * Returns true if thread was cancelled before being signalled.
     *
     * @param node the node
     * @return true if cancelled before the node was signalled
     */
    final boolean transferAfterCancelledWait(Node node) {
        //使用 cas 修改节点状态，如果还能修改成功， 说明线程被中断时， signal 还没有被调用。

        // 这里有一个知识点，就是线程被唤醒，并不一定是在 java 层面执行了locksupport.unpark，也可能是调用了线程
        // 的 interrupt()方法，这个方法会更新一个中 断标识，并且会唤醒处于阻塞状态下的线程。

        //在signal那边已经修改为0，这里如果能成功说明没有之后后面的enq代码
        //所以下面的unpark也不会执行，可能是通过中断被unpark的
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);//如果 cas 成功，则把node 添加到 AQS 队列
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        // cas失败，说明执行到下面了

        //如果 cas 失败，则判断当前 node 是否已经在 AQS 队列上，如果不在，则让给其他线程执行
        //当 node 被触发了 signal 方法时， node 就会被加到 aqs 队列上
        while (!isOnSyncQueue(node))//循环检测 node 是否已经成功添加到 AQS 队列中。如果没有，则通过 yield，
            Thread.yield();
        return false;
    }

    /**
     * Invokes release with current state value; returns saved state.
     * Cancels node and throws exception on failure.
     *
     * @param node the condition node for this wait
     * @return previous sync state
     */
    //fullRelease，就是彻底的释放锁，什么叫彻底呢，就是如果
    // 当前锁存在多次重入，那么在这个方法中只需要释放一次
    // 就会把所有的重入次数归零
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            int savedState = getState();//获得重入的次数
            if (release(savedState)) {
                // 释放锁并且唤醒下一个同步队列（aqs队列）中的线程
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * Queries whether the given ConditionObject
     * uses this synchronizer as its lock.
     *
     * @param condition the condition
     * @return {@code true} if owned
     * @throws NullPointerException if the condition is null
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * Queries whether any threads are waiting on the given condition
     * associated with this synchronizer. Note that because timeouts
     * and interrupts may occur at any time, a {@code true} return
     * does not guarantee that a future {@code signal} will awaken
     * any threads.  This method is designed primarily for use in
     * monitoring of the system state.
     *
     * @param condition the condition
     * @return {@code true} if there are any waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *                                      is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this synchronizer
     * @throws NullPointerException         if the condition is null
     */
    public final boolean hasWaiters(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * Returns an estimate of the number of threads waiting on the
     * given condition associated with this synchronizer. Note that
     * because timeouts and interrupts may occur at any time, the
     * estimate serves only as an upper bound on the actual number of
     * waiters.  This method is designed for use in monitoring of the
     * system state, not for synchronization control.
     *
     * @param condition the condition
     * @return the estimated number of waiting threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *                                      is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this synchronizer
     * @throws NullPointerException         if the condition is null
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * Returns a collection containing those threads that may be
     * waiting on the given condition associated with this
     * synchronizer.  Because the actual set of threads may change
     * dynamically while constructing this result, the returned
     * collection is only a best-effort estimate. The elements of the
     * returned collection are in no particular order.
     *
     * @param condition the condition
     * @return the collection of threads
     * @throws IllegalMonitorStateException if exclusive synchronization
     *                                      is not held
     * @throws IllegalArgumentException     if the given condition is
     *                                      not associated with this synchronizer
     * @throws NullPointerException         if the condition is null
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * Condition implementation for a {@link
     * AbstractQueuedSynchronizer} serving as the basis of a {@link
     * Lock} implementation.
     *
     * <p>Method documentation for this class describes mechanics,
     * not behavioral specifications from the point of view of Lock
     * and Condition users. Exported versions of this class will in
     * general need to be accompanied by documentation describing
     * condition semantics that rely on those of the associated
     * {@code AbstractQueuedSynchronizer}.
     *
     * <p>This class is Serializable, but all fields are transient,
     * so deserialized conditions have no waiters.
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /**
         * First node of condition queue.
         */
        private transient Node firstWaiter;
        /**
         * Last node of condition queue.
         */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() {
        }

        // Internal methods

        /**
         * Adds a new waiter to wait queue.
         *
         * @return its new wait node
         */
        // 这个方法的主要作用是把当前线程封装成 Node，添加到
        // 等待队列。 这里的队列不再是双向链表，而是单向链表
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            // 如 果 lastWaiter 不 等 于 空 并 且waitStatus 不等于 CONDITION 时，把取消节点从链表中移除
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            //构建一个 Node， waitStatus=CONDITION。这里的链表是一个单向的，所以相比 AQS 来说会简单很多
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null)
                firstWaiter = node;
            else
                t.nextWaiter = node;
            lastWaiter = node;
            return node;
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         *
         * @param first (non-null) the first node on condition queue
         */
        //对 condition 队列中从首部开始的第一个 condition 状态的
        // 节点，执行 transferForSignal 操作，将 node 从 condition
        // 队列中转换到 AQS 队列中，同时修改 AQS 队列中原先尾
        // 节点的状态
        private void doSignal(Node first) {
            do {
                if ((firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;// 将 next 节点设置成 null
                first.nextWaiter = null;
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        /**
         * Removes and transfers all nodes.
         *
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         * Unlinks cancelled waiter nodes from condition queue.
         * Called only while holding lock. This is called when
         * cancellation occurred during condition wait, and upon
         * insertion of a new waiter when lastWaiter is seen to have
         * been cancelled. This method is needed to avoid garbage
         * retention in the absence of signals. So even though it may
         * require a full traversal, it comes into play only when
         * timeouts or cancellations occur in the absence of
         * signals. It traverses all nodes rather than stopping at a
         * particular target to unlink all pointers to garbage nodes
         * without requiring many re-traversals during cancellation
         * storms.
         */
        //该方法就是从头到尾遍历Condition队列，移除状态为非CONDITION的节点。被取消的节点
        private void unlinkCancelledWaiters() {
            //获取等待队列中的头节点
            Node t = firstWaiter;
            Node trail = null;
            //遍历等待队列，将已经中断的线程节点从等待队列中移除。
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    t.nextWaiter = null;
                    // 第一次循环 trail 为空
                    if (trail == null)
                        firstWaiter = next;
                    else
                        //这里 trail 表示上一次循环的节点，即当前 t 的前节点
                        trail.nextWaiter = next;
                    if (next == null)//重新定义lastWaiter的指向
                        lastWaiter = trail;
                }
                else
                    trail = t;
                t = next;
            }
        }
        // private void unlinkCancelledWaiters() {
        //     Node t = firstWaiter;
        //     Node trail = null;
        //     while (t != null) {
        //         Node next = t.nextWaiter;
        //         if (t.waitStatus != Node.CONDITION) {
        //             t.nextWaiter = null;
        //             if (trail == null)
        //                 firstWaiter = next;
        //             else
        //                 trail.nextWaiter = next;
        //             if (next == null)
        //                 lastWaiter = trail;
        //         } else
        //             trail = t;
        //         t = next;
        //     }
        // }

        // public methods

        /**
         * Moves the longest-waiting thread, if one exists, from the
         * wait queue for this condition to the wait queue for the
         * owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        // await 方法会阻塞 ThreadA，然后 ThreadB 抢占到了锁获得了执行权限，这个时候在 ThreadB 中调用了 Condition
        // 的 signal()方法，将会唤醒在等待队列中节点
        public final void signal() {
            if (!isHeldExclusively())//先判断当前线程是否获得了锁，这个判断比较简单，直接用获得锁的线程和当前线程相比即可
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;// 拿到 Condition队列上第一个节点
            if (first != null)
                doSignal(first);
        }

        /**
         * Moves all threads from the wait queue for this condition to
         * the wait queue for the owning lock.
         *
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * Implements uninterruptible condition wait.
         * <ol>
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * </ol>
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /*
         * For interruptible waits, we need to track whether to throw
         * InterruptedException, if interrupted while blocked on
         * condition, versus reinterrupt current thread, if
         * interrupted while blocked waiting to re-acquire.
         */

        /**
         * Mode meaning to reinterrupt on exit from wait
         */
        private static final int REINTERRUPT = 1;
        /**
         * Mode meaning to throw InterruptedException on exit from wait
         */
        private static final int THROW_IE = -1;

        /**
         * Checks for interrupt, returning THROW_IE if interrupted
         * before signalled, REINTERRUPT if after signalled, or
         * 0 if not interrupted.
         */
        //前面在分析 await 方法时，线程会被阻塞。而通过 signal被唤醒之后又继续回到上次执行的逻辑中标注为红色部分
        // 的代码checkInterruptWhileWaiting 这个方法是干嘛呢？其实从
        // 名字就可以看出来，就是 ThreadA 在 condition 队列被阻塞的过程中，有没有被其他线程触发过中断请求

        //如果当前线程被中断，则调用transferAfterCancelledWait 方法判断后续的处理应该是
        // 抛出 InterruptedException 还是重新中断。这里需要注意的地方是，如果第一次 CAS 失败了，则不
        // 能判断当前线程是先进行了中断还是先进行了 signal 方法的调用，可能是先执行了 signal 然后中断，也可能是先执
        // 行了中断，后执行了 signal，当然，这两个操作肯定是发生在 CAS 之前。这时需要做的就是等待当前线程的 node
        // 被添加到 AQS 队列后，也就是 enq 方法返回后，返回false 告诉 checkInterruptWhileWaiting 方法返回
        // REINTERRUPT(1)，后续进行重新中断。简单来说，该方法的返回值代表当前线程是否在 park 的时候被中断唤醒，
        // 如果为 true 表示中断在 signal 调用之前， signal 还未执行，那么这个时候会根据 await 的语
        // 义，在 await 时遇到中断需要抛出interruptedException，返回 true 就是告诉checkInterruptWhileWaiting 返回 THROW_IE(-1)。
        // 如果返回 false，否则表示 signal 已经执行过了，只需要重新响应中断即可

        //等待状态下是否有收到中断请求
        private int checkInterruptWhileWaiting(Node node) {
            //返回当前中断状态
            return Thread.interrupted() ?
                    // 被中断过
                    //THROW_IE抛出异常、REINTERRUPT重新中断
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        //根据 checkInterruptWhileWaiting 方法返回的中断标识来进行中断上报。
        // 如果是 THROW_IE，则抛出中断异常如果是 REINTERRUPT，则重新响应中断
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */

        //我把前面的整个分解的图再通过一张整体的结构图来表
        // 述， 线程 awaitThread 先通过 lock.lock()方法获取锁成功后调用了 condition.await 方法进入等待队列，而另一个
        // 线程 signalThread 通过 lock.lock()方法获取锁成功后调用了 condition.signal 或者 signalAll 方法，使得线程
        // awaitThread 能够有机会移入到同步队列中，当其他线程释放 lock 后使得线程 awaitThread 能够有机会获取
        // lock，从而使得线程 awaitThread 能够从 await 方法中退出执行后续操作。如果 awaitThread 获取 lock 失败会直
        // 接进入到同步队列。阻塞： await()方法中，在线程释放锁资源之后，如果节点不在 AQS 等待队列，则阻塞当前线程，如果在等待队
        // 列，则自旋等待尝试获取锁释放： signal()后，节点会从 condition 队列移动到 AQS等待队列，则进入正常锁的获取流程

        //会使当前线程进入等待队列并释放锁，同时线程状态变为
        //等待状态。当从 await()方法返回时，当前线程一定获取了
        //Condition相关联的锁
        public final void await() throws InterruptedException {
            if (Thread.interrupted())//表示 await 允许被中断
                throw new InterruptedException();
            Node node = addConditionWaiter();//创建一个新的节点，节点状态为 condition，采用的数据结构仍然是链表
            //为什么是fully？因为可重入锁，可能有多重，假如重入五次，这里一次释放
            int savedState = fullyRelease(node);//释放当前的锁，得到锁的状态，并唤醒 AQS 队列中的一个线程
            // 方法中的state是当前重入的次数
            int interruptMode = 0;
            //如果当前节点没有在同步队列上，即还没有被 signal，则将当前线程阻塞
            while (!isOnSyncQueue(node)) {
                ////判断这个节点是否在 AQS 队列上，第一次判断的是 false，因为前面已经释放锁了
                //前面唤醒了head后面的节点，head就是当前线程，当前线程不再是owner了。
                LockSupport.park(this);//通过 park 挂起当前线程
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            // 当这个线程醒来,会尝试拿锁, 当 acquireQueued返回 false 就是拿到锁了.
            // interruptMode != THROW_IE -> 表示这个线程没有成功将 node 入队,但 signal 执行了 enq 方法让其入队了.
            // 将这个变量设置成 REINTERRUPT.
            //走到这里一定已经进入aqs队列了
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            // 如果 node 的下一个等待者不是 null, 则进行清理,清理 Condition 队列上的节点.
            // 如果是 null ,就没有什么好清理的了.
            if (node.nextWaiter != null) // clean up if cancelled
                unlinkCancelledWaiters();
            // 如果线程被中断了,需要抛出异常.或者什么都不做
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * Implements absolute timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * Implements timed condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled, interrupted, or timed out.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * <li> If timed out while blocked in step 4, return false, else true.
         * </ol>
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        //  support for instrumentation

        /**
         * Returns true if this condition was created by the given
         * synchronization object.
         *
         * @return {@code true} if owned
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * Queries whether any threads are waiting on this condition.
         * Implements {@link AbstractQueuedSynchronizer#hasWaiters(ConditionObject)}.
         *
         * @return {@code true} if there are any waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        protected final boolean hasWaiters() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * Returns an estimate of the number of threads waiting on
         * this condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitQueueLength(ConditionObject)}.
         *
         * @return the estimated number of waiting threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        protected final int getWaitQueueLength() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * Returns a collection containing those threads that may be
         * waiting on this Condition.
         * Implements {@link AbstractQueuedSynchronizer#getWaitingThreads(ConditionObject)}.
         *
         * @return the collection of threads
         * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
         *                                      returns {@code false}
         */
        protected final Collection<Thread> getWaitingThreads() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * Setup to support compareAndSet. We need to natively implement
     * this here: For the sake of permitting future enhancements, we
     * cannot explicitly subclass AtomicInteger, which would be
     * efficient and useful otherwise. So, as the lesser of evils, we
     * natively implement using hotspot intrinsics API. And while we
     * are at it, we do the same for other CASable fields (which could
     * otherwise be done with atomic field updaters).
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("next"));

        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
