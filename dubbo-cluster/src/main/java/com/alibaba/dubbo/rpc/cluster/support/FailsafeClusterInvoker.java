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
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.List;

/**
 * When invoke fails, log the error message and ignore this error by returning an empty RpcResult.
 * Usually used to write audit logs and other operations
 *
 * <a href="http://en.wikipedia.org/wiki/Fail-safe">Fail-safe</a>
 * 是一种失败安全的 Cluster Invoker。所谓的失败安全是指，当调用过程中出现异常时，FailsafeClusterInvoker 仅会打印异常，而不会抛出异常。
 * 适用于写入审计日志等操作。
 */
public class FailsafeClusterInvoker<T> extends AbstractClusterInvoker<T> {
    private static final Logger logger = LoggerFactory.getLogger(FailsafeClusterInvoker.class);

    public FailsafeClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    public Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            // 选择 Invoker
            Invoker<T> invoker = select(loadbalance, invocation, invokers, null);
            // 进行远程调用
            return invoker.invoke(invocation);
        } catch (Throwable e) {
            // 打印错误日志，但不抛出
            logger.error("Failsafe ignore exception: " + e.getMessage(), e);
            // 返回空结果忽略错误
            return new RpcResult(); // ignore
        }
    }
}
