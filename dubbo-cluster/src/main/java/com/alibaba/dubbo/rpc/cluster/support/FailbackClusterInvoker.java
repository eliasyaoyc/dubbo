/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.threadlocal.NamedInternalThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * When fails, record failure requests and schedule for retry on a regular interval.
 * Especially useful for services of notification.
 *
 * <a href="http://en.wikipedia.org/wiki/Failback">Failback</a>
 * 在调用失败后，返回一个空结果给服务提供者。并通过定时任务对失败的调用进行重传，适合执行消息通知等操作。
 */
public class FailbackClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(FailbackClusterInvoker.class);

    private static final long RETRY_FAILED_PERIOD = 5 * 1000;

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link com.alibaba.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link com.alibaba.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2,
            new NamedInternalThreadFactory("failback-cluster-timer", true));

    private final ConcurrentMap<Invocation, AbstractClusterInvoker<?>> failed = new ConcurrentHashMap<Invocation, AbstractClusterInvoker<?>>();
    private volatile ScheduledFuture<?> retryFuture;

    public FailbackClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    private void addFailed(Invocation invocation, AbstractClusterInvoker<?> router) {
        if (retryFuture == null) {
            synchronized (this) {
                if (retryFuture == null) {
                    // 创建定时任务，每隔5秒执行一次
                    retryFuture = scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {

                        @Override
                        public void run() {
                            // collect retry statistics
                            try {
                                // 对失败的调用进行重试
                                retryFailed();
                            } catch (Throwable t) { // Defensive fault toleranc
                                // 如果发生异常，仅打印异常日志，不抛出
                                logger.error("Unexpected error occur at collect statistic", t);
                            }
                        }
                    }, RETRY_FAILED_PERIOD, RETRY_FAILED_PERIOD, TimeUnit.MILLISECONDS);
                }
            }
        }
        // 添加 invocation 和 invoker 到 failed 中
        failed.put(invocation, router);
    }

    void retryFailed() {
        if (failed.size() == 0) {
            return;
        }
        // 遍历 failed，对失败的调用进行重试
        for (Map.Entry<Invocation, AbstractClusterInvoker<?>> entry : new HashMap<Invocation, AbstractClusterInvoker<?>>(
                failed).entrySet()) {
            Invocation invocation = entry.getKey();
            Invoker<?> invoker = entry.getValue();
            try {
                // 再次进行调用
                invoker.invoke(invocation);
                // 调用成功后，从 failed 中移除 invoker
                failed.remove(invocation);
            } catch (Throwable e) {
                logger.error("Failed retry to invoke method " + invocation.getMethodName() + ", waiting again.", e);
            }
        }
    }

    /**
     * 这个类主要由3个方法组成，首先是 doInvoker，该方法负责初次的远程调用。
     * 若远程调用失败，则通过 addFailed 方法将调用信息存入到 failed 中，等待定时重试。
     * addFailed 在开始阶段会根据 retryFuture 为空与否，来决定是否开启定时任务。
     * retryFailed 方法则是包含了失败重试的逻辑，该方法会对 failed 进行遍历，然后依次对 Invoker 进行调用。
     * 调用成功则将 Invoker 从 failed 中移除，调用失败则忽略失败原因。
     * @param invocation
     * @param invokers
     * @param loadbalance
     * @return
     * @throws RpcException
     */
    @Override
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            // 选择 Invoker
            Invoker<T> invoker = select(loadbalance, invocation, invokers, null);
            // 进行调用
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            // 如果调用过程中发生异常，此时仅打印错误日志，不抛出异常
            logger.error("Failback to invoke method " + invocation.getMethodName() + ", wait for retry in background. Ignored exception: "
                    + e.getMessage() + ", ", e);
            // 记录调用信息
            addFailed(invocation, this);
            // 返回一个空结果给服务消费者
            return new RpcResult(); // ignore
        }
    }

}
