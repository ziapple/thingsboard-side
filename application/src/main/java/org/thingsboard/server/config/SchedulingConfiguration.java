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
package org.thingsboard.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 启动Spring自带的定时任务功能,会扫描带@Scheduled的方法注解
 * 1.cron是设置定时执行的表达式,如 0 0/5 * * * ?每隔五分钟执行一次
 * 2.zone表示执行时间的时区
 * 3.fixedDelay 和fixedDelayString 一个固定延迟时间执行,上个任务完成后,延迟多久执行
 * 4.fixedRate 和fixedRateString一个固定频率执行,上个任务开始后多长时间后开始执行
 * 5.initialDelay 和initialDelayString表示一个初始延迟时间,第一次被调用前延迟的时间
 *
 * 在ThingsBoard中会在以下几个地方执行
 * 1. 定期打印Actor之间发送的消息，#{@link org.thingsboard.server.actors.service.DefaultActorService}
 *    默认为关闭,每10秒钟打印一次，在yml中cluster.stat配置
 * 2. 定期打印JVM Scripts的消息，#{@link org.thingsboard.server.service.script.RemoteJsInvokeService}
 *    默认为关闭，每10秒打印一次，在yml中js.remote.stats配置
 * 3. 定期打印Rule Engine规则引擎日志，#{@link org.thingsboard.server.service.transport.RemoteRuleEngineTransportService}
 *    默认为关闭，每10秒打印一次，在yml中transport.remote.rule_engine.stats配置
 * 4. 定期打印Canssandra当前正在处理的并发读写容量，#{@link org.thingsboard.server.dao.nosql.CassandraBufferedRateExecutor}
 *    打印Canssandra高并发情况下读写限制，如果开启tenant_rate_limits.enabled，会限制多租户读写Canssandra并发数量
 *    数据库并发读写限制模式，值得学习
 * 5. 定期打印Nashorn的执行情况，#{@link org.thingsboard.server.service.script.AbstractNashornJsInvokeService}
 *    Nashorn 扩展了 Java 在 JVM 上运行动态 JavaScript 脚本的能力
 *    默认为关闭，每10秒打印一次，在yml的js.remote.stats中配置
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(taskScheduler());
    }

    @Bean(destroyMethod="shutdown")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler threadPoolScheduler = new ThreadPoolTaskScheduler();
        threadPoolScheduler.setThreadNamePrefix("TB-Scheduling-");
        threadPoolScheduler.setPoolSize(Runtime.getRuntime().availableProcessors());
        threadPoolScheduler.setRemoveOnCancelPolicy(true);
        return threadPoolScheduler;
    }
}
