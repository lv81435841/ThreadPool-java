
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j(topic = "testpool")
public class TestPool {
    public static void main(String[] args) {
        ThreadPool threadPool = new ThreadPool(2, 1000, TimeUnit.MILLISECONDS, 2,(queue,task) -> {
            //queue.put(task);
            //queue.offer(task,timeout,TimeUnit);
            //放弃执行，啥都不干
            //throw new RuntimeException("队列满了");
            //dosomething();
        });
        for( int i = 0; i < 15; i++ ) {
            int j = i;
            threadPool.execute( () -> {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
               log.debug(String.valueOf(j));
            });
        }
    }
    static void dosomething() {
        log.info("dosomething");
    }
}

@FunctionalInterface
interface RejectPolicy<T>{
    void reject(BlockingQueue<T> queue, T task);
}

@Slf4j(topic = "testpool")
class ThreadPool{
    private BlockingQueue<Runnable> taskQueue;
    private HashSet<Worker> workers = new HashSet<>();
    private int coreSize;
    private int timeout;
    private TimeUnit TimeUnit;
    private RejectPolicy<Runnable> rejectPolicy;

    public ThreadPool(int coreSize, int timeout ,TimeUnit timeUnit, int queueCapacity, RejectPolicy<Runnable> rejectPolicy) {
        this.taskQueue = new BlockingQueue<Runnable>(queueCapacity);
        this.TimeUnit = timeUnit;
        this.coreSize = coreSize;
        this.timeout = timeout;
        this.rejectPolicy = rejectPolicy;
    }

    public void execute(Runnable task) {
        synchronized (workers) {
            if (workers.size() < coreSize) {
                Worker worker = new Worker(task);
                log.debug("Add worker thread{}",worker);
                workers.add(worker);
                worker.start();
            } else{
                //taskQueue.offer(task,timeout,TimeUnit);
                //taskQueue.put(task);
                taskQueue.tryPut(rejectPolicy,task);
            }
        }
    }

    class Worker extends Thread{
        private Runnable task;
        public Worker(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            while(task != null || (task = taskQueue.poll(timeout,TimeUnit)) != null){
                try{
                    log.debug("Working....{}",task);
                    task.run();

                }catch (Exception e){

                }finally {
                    task = null;
                }
            }
            synchronized (workers) {
                workers.remove(this);
                log.debug("Remove worker thread{}",this);
            }
        }

    }
}

@Slf4j(topic = "testpool")
class BlockingQueue<T> {
    private Deque<T> queue = new ArrayDeque<>();
    private ReentrantLock lock = new ReentrantLock();
    private Condition Empty = lock.newCondition();
    private Condition Full = lock.newCondition();
    private int capcity;

    public BlockingQueue(int capcity) {
        this.capcity = capcity;
    }

    public T take(){
        lock.lock();
        try{
            while(queue.isEmpty()){
                try {
                    Empty.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            T t = queue.removeFirst();
            Full.signal();
            return t;
        }finally {
            lock.unlock();
        }
    }

    public T poll(long timeout, TimeUnit unit){
        lock.lock();
        long nanos = unit.toNanos(timeout);
        try{
            while(queue.isEmpty()){
                try {
                    if(nanos <= 0){
                        return null;
                    }
                    nanos = Empty.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            T t = queue.removeFirst();
            Full.signal();
            return t;
        }finally {
            lock.unlock();
        }
    }

    public void put(T t){
        lock.lock();
        try {
            while(queue.size() == capcity){
                try {
                    log.debug("发满了");
                    Full.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            log.debug("加入队伍");
            queue.addLast(t);
            Empty.signal();
        }finally {
            lock.unlock();
        }
    }

    public boolean offer(T t,long timeout,TimeUnit unit){
        lock.lock();
        try {
            long nanos = unit.toNanos(timeout);
            while(queue.size() == capcity){
                try {
                    log.debug("发满了延时");
                    if(nanos <= 0){
                        log.debug("时间等完了");
                        return false;
                    }
                    nanos = Full.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            log.debug("加入队伍");
            queue.addLast(t);
            Empty.signal();
            return true;
        }finally {
            lock.unlock();
        }
    }



    public void tryPut(RejectPolicy<T> rejectPolicy, T task) {
        lock.lock();
        try {
            if(queue.size() == capcity){
                rejectPolicy.reject(this,task);
            }else{
                log.debug("加入队列");
                queue.addLast(task);
                Empty.signal();
            }
        }finally {
            lock.unlock();
        }
    }
}
