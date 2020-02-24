/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.sql;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.common.util.ThingsBoardThreadFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 一个执行SQL语句写的阻塞队列
 * 所有设备时序数据都在阻塞队列进行写入
 * @param <E>
 */
@Slf4j
public class TbSqlBlockingQueue<E> implements TbSqlQueue<E> {
    // 要写入的Entity阻塞队列
    private final BlockingQueue<TbSqlQueueElement<E>> queue = new LinkedBlockingQueue<>();
    // 当前队列新增的记录
    private final AtomicInteger addedCount = new AtomicInteger();
    // 当前队列成功保存到数据库的记录
    private final AtomicInteger savedCount = new AtomicInteger();
    // 当前队列失败写入数据库的记录，注：写入数据是批处理的，要么成功，要么失败
    private final AtomicInteger failedCount = new AtomicInteger();
    // 阻塞队列参数，规定写入最大延迟事件maxDelay，队列大小batchSize
    private final TbSqlBlockingQueueParams params;
    // 队列任务线程执行器
    private ExecutorService executor;
    // 定时打印日志任务线程
    private ScheduledLogExecutorComponent logExecutor;

    public TbSqlBlockingQueue(TbSqlBlockingQueueParams params) {
        this.params = params;
    }

    @Override
    public void init(ScheduledLogExecutorComponent logExecutor, Consumer<List<E>> saveFunction) {
        this.logExecutor = logExecutor;
        executor = Executors.newSingleThreadExecutor(ThingsBoardThreadFactory.forName("sql-queue-" + params.getLogName().toLowerCase()));
        executor.submit(() -> {
            String logName = params.getLogName();
            int batchSize = params.getBatchSize();
            long maxDelay = params.getMaxDelay();
            List<TbSqlQueueElement<E>> entities = new ArrayList<>(batchSize);
            while (!Thread.interrupted()) {
                try {
                    long currentTs = System.currentTimeMillis();
                    // 获取队列元素，队列为空时返回null
                    TbSqlQueueElement<E> attr = queue.poll(maxDelay, TimeUnit.MILLISECONDS);
                    if (attr == null) {
                        continue;
                    } else {
                        entities.add(attr);
                    }
                    // 从队列里面一次性拿出
                    queue.drainTo(entities, batchSize - 1);
                    boolean fullPack = entities.size() == batchSize;
                    log.debug("[{}] Going to save {} entities", logName, entities.size());
                    // 保存实体类（时序数据），把List<Entity>作为参数传过去批处理保存，保存方法在{@code JpaTimeserisDao}中实现
                    saveFunction.accept(entities.stream().map(TbSqlQueueElement::getEntity).collect(Collectors.toList()));
                    // 设置entities的Future返回值为null
                    entities.forEach(v -> v.getFuture().set(null));
                    // 记录保存成功的实体数量
                    savedCount.addAndGet(entities.size());
                    // 控制每个批次保存时间在maxDelay以内
                    if (!fullPack) {
                        long remainingDelay = maxDelay - (System.currentTimeMillis() - currentTs);
                        if (remainingDelay > 0) {
                            Thread.sleep(remainingDelay);
                        }
                    }
                } catch (Exception e) {
                    failedCount.addAndGet(entities.size());
                    // 设置entity执行失败的异常
                    entities.forEach(entityFutureWrapper -> entityFutureWrapper.getFuture().setException(e));
                    if (e instanceof InterruptedException) {
                        log.info("[{}] Queue polling was interrupted", logName);
                        break;
                    } else {
                        log.error("[{}] Failed to save {} entities", logName, entities.size(), e);
                    }
                } finally {
                    entities.clear();
                }
            }
        });

        // 定期清楚计数器，防止溢出
        logExecutor.scheduleAtFixedRate(() -> {
            if (queue.size() > 0 || addedCount.get() > 0 || savedCount.get() > 0 || failedCount.get() > 0) {
                log.info("[{}] queueSize [{}] totalAdded [{}] totalSaved [{}] totalFailed [{}]",
                        params.getLogName(), queue.size(), addedCount.getAndSet(0), savedCount.getAndSet(0), failedCount.getAndSet(0));
            }
        }, params.getStatsPrintIntervalMs(), params.getStatsPrintIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public ListenableFuture<Void> add(E element) {
        SettableFuture<Void> future = SettableFuture.create();
        queue.add(new TbSqlQueueElement<>(future, element));
        addedCount.incrementAndGet();
        return future;
    }
}
