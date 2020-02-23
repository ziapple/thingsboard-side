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
package org.thingsboard.server.common.transport.service;

import lombok.Data;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.gen.transport.TransportProtos;

import java.util.concurrent.ScheduledFuture;

/**
 * 会话元数据，包含：
 * - 会话信息sessionInfo（会话id、设备id、租户id）
 * - 会话类型sessionType（同步、异步）
 * - 会话监听器（监听设备属性更新、发送RPC等事件）
 * - 最后一次活动时间
 * - 最后一次上报时间
 */
@Data
class SessionMetaData {

    private final TransportProtos.SessionInfoProto sessionInfo;
    private final TransportProtos.SessionType sessionType;
    private final SessionMsgListener listener;

    private ScheduledFuture scheduledFuture;

    private volatile long lastActivityTime;
    private volatile long lastReportedActivityTime;
    private volatile boolean subscribedToAttributes;
    private volatile boolean subscribedToRPC;

    SessionMetaData(
            TransportProtos.SessionInfoProto sessionInfo,
            TransportProtos.SessionType sessionType,
            SessionMsgListener listener
    ) {
        this.sessionInfo = sessionInfo;
        this.sessionType = sessionType;
        this.listener = listener;
        this.lastActivityTime = System.currentTimeMillis();
        this.scheduledFuture = null;
    }

    void updateLastActivityTime() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    void setScheduledFuture(ScheduledFuture scheduledFuture) { this.scheduledFuture = scheduledFuture; }

    public ScheduledFuture getScheduledFuture() {
        return scheduledFuture;
    }

    public boolean hasScheduledFuture() { return null != this.scheduledFuture; }
}
