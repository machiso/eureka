package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    private final InstanceInfo instanceInfo;

    private final int replicationIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Future> scheduledPeriodicRef;

    private final AtomicBoolean started;
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {

            //注册的时候将instanceinfo的isInstanceInfoDirty设置为true，默认是false
            instanceInfo.setIsDirty();  // for initial register

            //启动了一个定时任务，将自己放了进入，40s之后执行
            //InstanceInfoReplicator它自己本身其实就是一个实现了runnable接口的线程
            //此时应该去看一下run方法的具体实现，里面会有对应的注册的入口
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    public boolean onDemandUpdate() {
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
    
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);
                        }
    
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    public void run() {
        try {
            discoveryClient.refreshInstanceInfo();

            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                /**
                 * 这里就是register的入口了，真的太难发现了
                 * 注册的代码，通过DiscoveryClient中的通信组件EurekaTransport，底层通过jersey框架去向server端发送注册的请求
                 * 注册完成之后，再将instanceinfo isDirty设置为false
                 */
                discoveryClient.register();
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

}
