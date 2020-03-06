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
package org.thingsboard.server.service.transport;

import akka.actor.ActorRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.DonAsynchron;
import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.cluster.ServerAddress;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.service.AbstractTransportService;
import org.thingsboard.server.dao.device.ClaimDevicesService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.ClaimDeviceMsg;
import org.thingsboard.server.gen.transport.TransportProtos.DeviceActorToTransportMsg;
import org.thingsboard.server.gen.transport.TransportProtos.GetAttributeRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.GetOrCreateDeviceFromGatewayRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.GetOrCreateDeviceFromGatewayResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.PostAttributeMsg;
import org.thingsboard.server.gen.transport.TransportProtos.PostTelemetryMsg;
import org.thingsboard.server.gen.transport.TransportProtos.SessionEventMsg;
import org.thingsboard.server.gen.transport.TransportProtos.SessionInfoProto;
import org.thingsboard.server.gen.transport.TransportProtos.SubscribeToAttributeUpdatesMsg;
import org.thingsboard.server.gen.transport.TransportProtos.SubscribeToRPCMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToDeviceRpcResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ToServerRpcRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.TransportApiRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.TransportToDeviceActorMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceCredentialsResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceTokenRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceX509CertRequestMsg;
import org.thingsboard.server.service.cluster.routing.ClusterRoutingService;
import org.thingsboard.server.service.cluster.rpc.ClusterRpcService;
import org.thingsboard.server.service.encoding.DataDecodingEncodingService;
import org.thingsboard.server.service.transport.msg.TransportToDeviceActorMsgWrapper;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 处理Mqtt、Http的服务：本地模式
 * 1. 如果transport.type=local,采用本地模式
 *  - 对所有请求交给{@code DonAsynchron.withcallback}异步回调线程进行处理
 *  - 业务逻辑由{@code LocalTransportApiService}完成
 *  - 业务逻辑都是本地的Future任务，直接查询本地数据库
 * 2. 如果transport.type=remote,采用远程模式{@code RemoteRuleEngineTransportService}
 *  - 对所有请求交给{@code AsyncCallbackTemplate.withCallback}异步回调线程进行处理
 *  - 业务逻辑由{@code TbKafkaRequestTemplate}和{@code TBKafkaProducerTemplate}处理
 *  - 业务逻辑这两个类会发送任务消息给Kafka
 * 3. local和remote共同点
 *  - 所有请求都封装成 protobuf格式
 *  - 所有请求都转发给Actor集群处理
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "transport", value = "type", havingValue = "local")
public class LocalTransportService extends AbstractTransportService implements RuleEngineTransportService {

    @Autowired
    private TransportApiService transportApiService;

    @Autowired
    private ActorSystemContext actorContext;

    //TODO: completely replace this routing with the Kafka routing by partition ids.
    @Autowired
    private ClusterRoutingService routingService;
    @Autowired
    private ClusterRpcService rpcService;
    @Autowired
    private DataDecodingEncodingService encodingService;
    @Autowired
    private ClaimDevicesService claimDevicesService;

    @PostConstruct
    public void init() {
        super.init();
    }

    @PreDestroy
    public void destroy() {
        super.destroy();
    }

    /**
     * 处理验证设备Token的消息
     * @param msg
     * @param callback
     */
    @Override
    public void process(ValidateDeviceTokenRequestMsg msg, TransportServiceCallback<ValidateDeviceCredentialsResponseMsg> callback) {
        DonAsynchron.withCallback(
                transportApiService.handle(TransportApiRequestMsg.newBuilder().setValidateTokenRequestMsg(msg).build()),
                transportApiResponseMsg -> {
                    if (callback != null) {
                        callback.onSuccess(transportApiResponseMsg.getValidateTokenResponseMsg());
                    }
                },
                getThrowableConsumer(callback), transportCallbackExecutor);
    }

    /**
     * 处理验证设备证书的消息
     * @param msg
     * @param callback
     */
    @Override
    public void process(ValidateDeviceX509CertRequestMsg msg, TransportServiceCallback<ValidateDeviceCredentialsResponseMsg> callback) {
        DonAsynchron.withCallback(
                transportApiService.handle(TransportApiRequestMsg.newBuilder().setValidateX509CertRequestMsg(msg).build()),
                transportApiResponseMsg -> {
                    if (callback != null) {
                        callback.onSuccess(transportApiResponseMsg.getValidateTokenResponseMsg());
                    }
                },
                getThrowableConsumer(callback), transportCallbackExecutor);
    }

    @Override
    public void process(GetOrCreateDeviceFromGatewayRequestMsg msg, TransportServiceCallback<GetOrCreateDeviceFromGatewayResponseMsg> callback) {
        DonAsynchron.withCallback(
                transportApiService.handle(TransportApiRequestMsg.newBuilder().setGetOrCreateDeviceRequestMsg(msg).build()),
                transportApiResponseMsg -> {
                    if (callback != null) {
                        callback.onSuccess(transportApiResponseMsg.getGetOrCreateDeviceResponseMsg());
                    }
                },
                getThrowableConsumer(callback), transportCallbackExecutor);
    }

    /**
     * 处理Sessioin会话事件消息
     * 0-Open
     * 1-Close
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    protected void doProcess(SessionInfoProto sessionInfo, SessionEventMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setSessionEvent(msg).build(), callback);
    }

    /**
     * 处理设备时序数据发送消息
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    protected void doProcess(SessionInfoProto sessionInfo, PostTelemetryMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setPostTelemetry(msg).build(), callback);
    }

    /**
     * 处理设备属性消息
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    protected void doProcess(SessionInfoProto sessionInfo, PostAttributeMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setPostAttributes(msg).build(), callback);
    }

    /**
     * 获取设备属性数据
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    protected void doProcess(SessionInfoProto sessionInfo, GetAttributeRequestMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setGetAttributes(msg).build(), callback);
    }

    /**
     * 订阅RPC
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    public void process(SessionInfoProto sessionInfo, TransportProtos.SubscriptionInfoProto msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setSubscriptionInfo(msg).build(), callback);
    }

    /**
     * 订阅属性更新
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    protected void doProcess(SessionInfoProto sessionInfo, SubscribeToAttributeUpdatesMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setSubscribeToAttributes(msg).build(), callback);
    }

    @Override
    protected void doProcess(SessionInfoProto sessionInfo, SubscribeToRPCMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setSubscribeToRPC(msg).build(), callback);
    }

    /**
     * 给设备发送给RPC反馈
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    protected void doProcess(SessionInfoProto sessionInfo, ToDeviceRpcResponseMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setToDeviceRPCCallResponse(msg).build(), callback);
    }

    /**
     * 发送Server端RPC请求
     * @param sessionInfo
     * @param msg
     * @param callback
     */
    @Override
    protected void doProcess(SessionInfoProto sessionInfo, ToServerRpcRequestMsg msg, TransportServiceCallback<Void> callback) {
        forwardToDeviceActor(TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setToServerRPCCallRequest(msg).build(), callback);
    }

    @Override
    protected void registerClaimingInfo(SessionInfoProto sessionInfo, ClaimDeviceMsg msg, TransportServiceCallback<Void> callback) {
        TransportToDeviceActorMsg toDeviceActorMsg = TransportToDeviceActorMsg.newBuilder().setSessionInfo(sessionInfo).setClaimDevice(msg).build();

        TransportToDeviceActorMsgWrapper wrapper = new TransportToDeviceActorMsgWrapper(toDeviceActorMsg);
        Optional<ServerAddress> address = routingService.resolveById(wrapper.getDeviceId());
        if (address.isPresent()) {
            rpcService.tell(encodingService.convertToProtoDataMessage(address.get(), wrapper));
            callback.onSuccess(null);
        } else {
            TenantId tenantId = new TenantId(new UUID(sessionInfo.getTenantIdMSB(), sessionInfo.getTenantIdLSB()));
            DeviceId deviceId = new DeviceId(new UUID(msg.getDeviceIdMSB(), msg.getDeviceIdLSB()));
            DonAsynchron.withCallback(claimDevicesService.registerClaimingInfo(tenantId, deviceId, msg.getSecretKey(), msg.getDurationMs()),
                    callback::onSuccess, callback::onError);
        }
    }

    @Override
    public void process(String nodeId, DeviceActorToTransportMsg msg) {
        process(nodeId, msg, null, null);
    }

    @Override
    public void process(String nodeId, DeviceActorToTransportMsg msg, Runnable onSuccess, Consumer<Throwable> onFailure) {
        processToTransportMsg(msg);
        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    /**
     * 交给Actor分布式处理消息
     * 1. 如果是zk的cluster集群，调用rpcService
     * 2. 非集群模式，调用AppActor{@code AppActor},MsgType为TRANSPORT_TO_DEVICE_ACTOR_MSG
     * @param toDeviceActorMsg
     * @param callback
     */
    private void forwardToDeviceActor(TransportToDeviceActorMsg toDeviceActorMsg, TransportServiceCallback<Void> callback) {
        TransportToDeviceActorMsgWrapper wrapper = new TransportToDeviceActorMsgWrapper(toDeviceActorMsg);
        Optional<ServerAddress> address = routingService.resolveById(wrapper.getDeviceId());
        if (address.isPresent()) {
            rpcService.tell(encodingService.convertToProtoDataMessage(address.get(), wrapper));
        } else {
            actorContext.getAppActor().tell(wrapper, ActorRef.noSender());
        }
        if (callback != null) {
            callback.onSuccess(null);
        }
    }

    private <T> Consumer<Throwable> getThrowableConsumer(TransportServiceCallback<T> callback) {
        return e -> {
            if (callback != null) {
                callback.onError(e);
            }
        };
    }

}
