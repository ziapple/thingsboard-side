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
package org.thingsboard.server.service.transport.msg;

import lombok.Data;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.MsgType;
import org.thingsboard.server.common.msg.TbActorMsg;
import org.thingsboard.server.common.msg.aware.DeviceAwareMsg;
import org.thingsboard.server.common.msg.aware.TenantAwareMsg;
import org.thingsboard.server.gen.transport.TransportProtos.TransportToDeviceActorMsg;

import java.io.Serializable;
import java.util.UUID;

/**
 * Transport接受到消息后传输给Actor系统的消息封装
 * 1. TbActorMsg，获取消息类型接口
 * 2. DeviceAwareMsg，获取设备Id接口
 * 3. TenantAwareMsg，获取租户Id接口
 * 4. TransportToDeviceActorMsg的消息格式如下：
 *   SessionInfoProto sessionInfo = 1;    Session信息，包含SessionId，设备和租户Id
 *   SessionEventMsg sessionEvent = 2;    Session事件类型，Open或者Closed
 *   PostTelemetryMsg postTelemetry = 3;  时序数据
 *   PostAttributeMsg postAttributes = 4;
 *   GetAttributeRequestMsg getAttributes = 5;
 *   SubscribeToAttributeUpdatesMsg subscribeToAttributes = 6;
 *   SubscribeToRPCMsg subscribeToRPC = 7;
 *   ToDeviceRpcResponseMsg toDeviceRPCCallResponse = 8;
 *   ToServerRpcRequestMsg toServerRPCCallRequest = 9;
 *   SubscriptionInfoProto subscriptionInfo = 10;
 *   ClaimDeviceMsg claimDevice = 11;
 * Created by ashvayka on 09.10.18.
 */
@Data
public class TransportToDeviceActorMsgWrapper implements TbActorMsg, DeviceAwareMsg, TenantAwareMsg, Serializable {

    private final TenantId tenantId;
    private final DeviceId deviceId;
    private final TransportToDeviceActorMsg msg;

    public TransportToDeviceActorMsgWrapper(TransportToDeviceActorMsg msg) {
        this.msg = msg;
        this.tenantId = new TenantId(new UUID(msg.getSessionInfo().getTenantIdMSB(), msg.getSessionInfo().getTenantIdLSB()));
        this.deviceId = new DeviceId(new UUID(msg.getSessionInfo().getDeviceIdMSB(), msg.getSessionInfo().getDeviceIdLSB()));
    }

    @Override
    public MsgType getMsgType() {
        return MsgType.TRANSPORT_TO_DEVICE_ACTOR_MSG;
    }
}
