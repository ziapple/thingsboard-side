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
package org.thingsboard.server.transport.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnAckVariableHeader;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttPubAckMessage;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.thingsboard.server.common.msg.EncryptionUtil;
import org.thingsboard.server.common.transport.SessionMsgListener;
import org.thingsboard.server.common.transport.TransportService;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.common.transport.adaptor.AdaptorException;
import org.thingsboard.server.common.transport.service.AbstractTransportService;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.DeviceInfoProto;
import org.thingsboard.server.gen.transport.TransportProtos.SessionEvent;
import org.thingsboard.server.gen.transport.TransportProtos.SessionInfoProto;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceCredentialsResponseMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceTokenRequestMsg;
import org.thingsboard.server.gen.transport.TransportProtos.ValidateDeviceX509CertRequestMsg;
import org.thingsboard.server.transport.mqtt.adaptors.MqttTransportAdaptor;
import org.thingsboard.server.transport.mqtt.session.DeviceSessionCtx;
import org.thingsboard.server.transport.mqtt.session.GatewaySessionHandler;
import org.thingsboard.server.transport.mqtt.session.MqttTopicMatcher;
import org.thingsboard.server.transport.mqtt.util.SslUtil;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.CONNECTION_ACCEPTED;
import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD;
import static io.netty.handler.codec.mqtt.MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED;
import static io.netty.handler.codec.mqtt.MqttMessageType.CONNACK;
import static io.netty.handler.codec.mqtt.MqttMessageType.PINGRESP;
import static io.netty.handler.codec.mqtt.MqttMessageType.PUBACK;
import static io.netty.handler.codec.mqtt.MqttMessageType.SUBACK;
import static io.netty.handler.codec.mqtt.MqttMessageType.UNSUBACK;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_LEAST_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_MOST_ONCE;
import static io.netty.handler.codec.mqtt.MqttQoS.FAILURE;

/**
 * @author Andrew Shvayka
 */
@Slf4j
public class MqttTransportHandler extends ChannelInboundHandlerAdapter implements GenericFutureListener<Future<? super Void>>, SessionMsgListener {
    // Mqtt支持的最大Qos水平，最少发布一次Qos=1
    private static final MqttQoS MAX_SUPPORTED_QOS_LVL = AT_LEAST_ONCE;
    // 会话Id
    private final UUID sessionId;
    // Mqtt服务上下文，包含Mqtt消息转化器MqttTransportAdaptor（Json转RPC的Proto对象）, ssl数字证书提供器SslHandler
    private final MqttTransportContext context;
    // Mqtt消息转化器（Json转RPC的Proto对象）
    private final MqttTransportAdaptor adaptor;
    // 处理Mqtt消息的后端服务
    private final TransportService transportService;
    // ssl数字证书提供器
    private final SslHandler sslHandler;
    // Mqtt消息主题Qos的Map
    private final ConcurrentMap<MqttTopicMatcher, Integer> mqttQoSMap;
    // 连接会话信息,包含会话Id，设备Id，租户Id
    private volatile SessionInfoProto sessionInfo;
    // 客户端连接地址
    private volatile InetSocketAddress address;
    /**
     * 设备连接会话上下文
     * - 会话Id，sessionId
     * - 设备实例，deviceInfo，根据设备实例是否存在来判断设备是否连接
     * - 主题Qos水平，ConcurrentHashMap
     */
    private volatile DeviceSessionCtx deviceSessionCtx;
    // 网关处理器
    private volatile GatewaySessionHandler gatewaySessionHandler;

    MqttTransportHandler(MqttTransportContext context) {
        this.sessionId = UUID.randomUUID();
        this.context = context;
        this.transportService = context.getTransportService();
        this.adaptor = context.getAdaptor();
        this.sslHandler = context.getSslHandler();
        this.mqttQoSMap = new ConcurrentHashMap<>();
        this.deviceSessionCtx = new DeviceSessionCtx(sessionId, mqttQoSMap);
    }

    /**
     * 读取Channel过来的消息
     * @param ctx
     * @param msg
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        log.trace("[{}] Processing msg: {}", sessionId, msg);
        try {
            if (msg instanceof MqttMessage) {
                processMqttMsg(ctx, (MqttMessage) msg);
            } else {
                ctx.close();
            }
        } finally {
            ReferenceCountUtil.safeRelease(msg);
        }
    }

    /**
     * 处理收到的Mqtt消息
     * @param ctx
     * @param msg
     */
    private void processMqttMsg(ChannelHandlerContext ctx, MqttMessage msg) {
        address = (InetSocketAddress) ctx.channel().remoteAddress();
        if (msg.fixedHeader() == null) {
            log.info("[{}:{}] Invalid message received", address.getHostName(), address.getPort());
            processDisconnect(ctx);
            return;
        }
        deviceSessionCtx.setChannel(ctx);
        switch (msg.fixedHeader().messageType()) {
            case CONNECT:   //连接
                processConnect(ctx, (MqttConnectMessage) msg);
                break;
            case PUBLISH:   //发布
                processPublish(ctx, (MqttPublishMessage) msg);
                break;
            case SUBSCRIBE: //订阅
                processSubscribe(ctx, (MqttSubscribeMessage) msg);
                break;
            case UNSUBSCRIBE: //取消订阅
                processUnsubscribe(ctx, (MqttUnsubscribeMessage) msg);
                break;
            case PINGREQ:   //PING请求
                if (checkConnected(ctx, msg)) {
                    ctx.writeAndFlush(new MqttMessage(new MqttFixedHeader(PINGRESP, false, AT_MOST_ONCE, false, 0)));
                    // 更新设备session最新活跃时间
                    transportService.reportActivity(sessionInfo);
                    // 更新网关session最新活跃时间
                    if (gatewaySessionHandler != null) {
                        gatewaySessionHandler.reportActivity();
                    }
                }
                break;
            case DISCONNECT:    //断开连接
                if (checkConnected(ctx, msg)) {
                    processDisconnect(ctx);
                }
                break;
            default:
                break;
        }

    }

    /**
     * 处理消息发布
     * @param ctx
     * @param mqttMsg
     */
    private void processPublish(ChannelHandlerContext ctx, MqttPublishMessage mqttMsg) {
        if (!checkConnected(ctx, mqttMsg)) {
            return;
        }
        // 获取消息topic和消息id
        String topicName = mqttMsg.variableHeader().topicName();
        int msgId = mqttMsg.variableHeader().packetId();
        log.trace("[{}][{}] Processing publish msg [{}][{}]!", sessionId, deviceSessionCtx.getDeviceId(), topicName, msgId);

        if (topicName.startsWith(MqttTopics.BASE_GATEWAY_API_TOPIC)) {
            if (gatewaySessionHandler != null) {
                // 处理网关消息发布
                handleGatewayPublishMsg(topicName, msgId, mqttMsg);
            }
        } else {
            // 处理设备消息发布
            processDevicePublish(ctx, mqttMsg, topicName, msgId);
        }
    }

    /**
     * 处理网关的消息发布
     * @param topicName
     * @param msgId
     * @param mqttMsg
     */
    private void handleGatewayPublishMsg(String topicName, int msgId, MqttPublishMessage mqttMsg) {
        try {
            switch (topicName) {
                case MqttTopics.GATEWAY_TELEMETRY_TOPIC: // 发布时序数据
                    gatewaySessionHandler.onDeviceTelemetry(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_CLAIM_TOPIC:    //发布
                    gatewaySessionHandler.onDeviceClaim(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC: //修改设备属性
                    gatewaySessionHandler.onDeviceAttributes(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_ATTRIBUTES_REQUEST_TOPIC://设备属性请求
                    gatewaySessionHandler.onDeviceAttributesRequest(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_RPC_TOPIC:  //网关RPC请求
                    gatewaySessionHandler.onDeviceRpcResponse(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_CONNECT_TOPIC:  //网关连接
                    gatewaySessionHandler.onDeviceConnect(mqttMsg);
                    break;
                case MqttTopics.GATEWAY_DISCONNECT_TOPIC:   //网关断开连接
                    gatewaySessionHandler.onDeviceDisconnect(mqttMsg);
                    break;
            }
        } catch (RuntimeException | AdaptorException e) {
            log.warn("[{}] Failed to process publish msg [{}][{}]", sessionId, topicName, msgId, e);
        }
    }

    private void processDevicePublish(ChannelHandlerContext ctx, MqttPublishMessage mqttMsg, String topicName, int msgId) {
        try {
            if (topicName.equals(MqttTopics.DEVICE_TELEMETRY_TOPIC)) {// 时序数据
                TransportProtos.PostTelemetryMsg postTelemetryMsg = adaptor.convertToPostTelemetry(deviceSessionCtx, mqttMsg);
                transportService.process(sessionInfo, postTelemetryMsg, getPubAckCallback(ctx, msgId, postTelemetryMsg));
            } else if (topicName.equals(MqttTopics.DEVICE_ATTRIBUTES_TOPIC)) {
                TransportProtos.PostAttributeMsg postAttributeMsg = adaptor.convertToPostAttributes(deviceSessionCtx, mqttMsg);
                transportService.process(sessionInfo, postAttributeMsg, getPubAckCallback(ctx, msgId, postAttributeMsg));
            } else if (topicName.startsWith(MqttTopics.DEVICE_ATTRIBUTES_REQUEST_TOPIC_PREFIX)) {
                TransportProtos.GetAttributeRequestMsg getAttributeMsg = adaptor.convertToGetAttributes(deviceSessionCtx, mqttMsg);
                transportService.process(sessionInfo, getAttributeMsg, getPubAckCallback(ctx, msgId, getAttributeMsg));
            } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_RESPONSE_TOPIC)) {
                TransportProtos.ToDeviceRpcResponseMsg rpcResponseMsg = adaptor.convertToDeviceRpcResponse(deviceSessionCtx, mqttMsg);
                transportService.process(sessionInfo, rpcResponseMsg, getPubAckCallback(ctx, msgId, rpcResponseMsg));
            } else if (topicName.startsWith(MqttTopics.DEVICE_RPC_REQUESTS_TOPIC)) {
                TransportProtos.ToServerRpcRequestMsg rpcRequestMsg = adaptor.convertToServerRpcRequest(deviceSessionCtx, mqttMsg);
                transportService.process(sessionInfo, rpcRequestMsg, getPubAckCallback(ctx, msgId, rpcRequestMsg));
            } else if (topicName.equals(MqttTopics.DEVICE_CLAIM_TOPIC)) {
                TransportProtos.ClaimDeviceMsg claimDeviceMsg = adaptor.convertToClaimDevice(deviceSessionCtx, mqttMsg);
                transportService.process(sessionInfo, claimDeviceMsg, getPubAckCallback(ctx, msgId, claimDeviceMsg));
            }
        } catch (AdaptorException e) {
            log.warn("[{}] Failed to process publish msg [{}][{}]", sessionId, topicName, msgId, e);
            log.info("[{}] Closing current session due to invalid publish msg [{}][{}]", sessionId, topicName, msgId);
            ctx.close();
        }
    }

    private <T> TransportServiceCallback<Void> getPubAckCallback(final ChannelHandlerContext ctx, final int msgId, final T msg) {
        return new TransportServiceCallback<Void>() {
            @Override
            public void onSuccess(Void dummy) {
                log.trace("[{}] Published msg: {}", sessionId, msg);
                if (msgId > 0) {
                    ctx.writeAndFlush(createMqttPubAckMsg(msgId));
                }
            }

            @Override
            public void onError(Throwable e) {
                log.trace("[{}] Failed to publish msg: {}", sessionId, msg, e);
                processDisconnect(ctx);
            }
        };
    }

    private void processSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage mqttMsg) {
        if (!checkConnected(ctx, mqttMsg)) {
            return;
        }
        log.trace("[{}] Processing subscription [{}]!", sessionId, mqttMsg.variableHeader().messageId());
        List<Integer> grantedQoSList = new ArrayList<>();
        for (MqttTopicSubscription subscription : mqttMsg.payload().topicSubscriptions()) {
            String topic = subscription.topicName();
            MqttQoS reqQoS = subscription.qualityOfService();
            try {
                switch (topic) {
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToAttributeUpdatesMsg.newBuilder().build(), null);
                        registerSubQoS(topic, grantedQoSList, reqQoS);
                        break;
                    }
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToRPCMsg.newBuilder().build(), null);
                        registerSubQoS(topic, grantedQoSList, reqQoS);
                        break;
                    }
                    case MqttTopics.DEVICE_RPC_RESPONSE_SUB_TOPIC:
                    case MqttTopics.GATEWAY_ATTRIBUTES_TOPIC:
                    case MqttTopics.GATEWAY_RPC_TOPIC:
                    case MqttTopics.GATEWAY_ATTRIBUTES_RESPONSE_TOPIC:
                    case MqttTopics.DEVICE_ATTRIBUTES_RESPONSES_TOPIC:
                        registerSubQoS(topic, grantedQoSList, reqQoS);
                        break;
                    default:
                        log.warn("[{}] Failed to subscribe to [{}][{}]", sessionId, topic, reqQoS);
                        grantedQoSList.add(FAILURE.value());
                        break;
                }
            } catch (Exception e) {
                log.warn("[{}] Failed to subscribe to [{}][{}]", sessionId, topic, reqQoS);
                grantedQoSList.add(FAILURE.value());
            }
        }
        ctx.writeAndFlush(createSubAckMessage(mqttMsg.variableHeader().messageId(), grantedQoSList));
    }

    private void registerSubQoS(String topic, List<Integer> grantedQoSList, MqttQoS reqQoS) {
        grantedQoSList.add(getMinSupportedQos(reqQoS));
        mqttQoSMap.put(new MqttTopicMatcher(topic), getMinSupportedQos(reqQoS));
    }

    private void processUnsubscribe(ChannelHandlerContext ctx, MqttUnsubscribeMessage mqttMsg) {
        if (!checkConnected(ctx, mqttMsg)) {
            return;
        }
        log.trace("[{}] Processing subscription [{}]!", sessionId, mqttMsg.variableHeader().messageId());
        for (String topicName : mqttMsg.payload().topics()) {
            mqttQoSMap.remove(new MqttTopicMatcher(topicName));
            try {
                switch (topicName) {
                    case MqttTopics.DEVICE_ATTRIBUTES_TOPIC: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToAttributeUpdatesMsg.newBuilder().setUnsubscribe(true).build(), null);
                        break;
                    }
                    case MqttTopics.DEVICE_RPC_REQUESTS_SUB_TOPIC: {
                        transportService.process(sessionInfo, TransportProtos.SubscribeToRPCMsg.newBuilder().setUnsubscribe(true).build(), null);
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("[{}] Failed to process unsubscription [{}] to [{}]", sessionId, mqttMsg.variableHeader().messageId(), topicName);
            }
        }
        ctx.writeAndFlush(createUnSubAckMessage(mqttMsg.variableHeader().messageId()));
    }

    private MqttMessage createUnSubAckMessage(int msgId) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(UNSUBACK, false, AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader mqttMessageIdVariableHeader = MqttMessageIdVariableHeader.from(msgId);
        return new MqttMessage(mqttFixedHeader, mqttMessageIdVariableHeader);
    }

    /**
     * Mqtt连接处理
     * @param ctx
     * @param msg
     */
    private void processConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
        log.info("[{}] Processing connect msg for client: {}!", sessionId, msg.payload().clientIdentifier());
        X509Certificate cert;
        if (sslHandler != null && (cert = getX509Certificate()) != null) {
            processX509CertConnect(ctx, cert);
        } else {
            processAuthTokenConnect(ctx, msg);
        }
    }

    /**
     * token设备连接认证
     * @param ctx
     * @param msg
     */
    private void processAuthTokenConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
        String userName = msg.payload().userName();
        log.info("[{}] Processing connect msg for client with user name: {}!", sessionId, userName);
        if (StringUtils.isEmpty(userName)) {
            ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD));
            ctx.close();
        } else {
            transportService.process(ValidateDeviceTokenRequestMsg.newBuilder().setToken(userName).build(),
                    new TransportServiceCallback<ValidateDeviceCredentialsResponseMsg>() {
                        @Override
                        public void onSuccess(ValidateDeviceCredentialsResponseMsg msg) {
                            // 认证成功后，建立设备会话上下文deviceSessionCtx
                            onValidateDeviceResponse(msg, ctx);
                        }

                        @Override
                        public void onError(Throwable e) {
                            log.trace("[{}] Failed to process credentials: {}", address, userName, e);
                            ctx.writeAndFlush(createMqttConnAckMsg(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE));
                            ctx.close();
                        }
                    });
        }
    }

    private void processX509CertConnect(ChannelHandlerContext ctx, X509Certificate cert) {
        try {
            String strCert = SslUtil.getX509CertificateString(cert);
            String sha3Hash = EncryptionUtil.getSha3Hash(strCert);
            transportService.process(ValidateDeviceX509CertRequestMsg.newBuilder().setHash(sha3Hash).build(),
                    new TransportServiceCallback<ValidateDeviceCredentialsResponseMsg>() {
                        @Override
                        public void onSuccess(ValidateDeviceCredentialsResponseMsg msg) {
                            onValidateDeviceResponse(msg, ctx);
                        }

                        @Override
                        public void onError(Throwable e) {
                            log.trace("[{}] Failed to process credentials: {}", address, sha3Hash, e);
                            ctx.writeAndFlush(createMqttConnAckMsg(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE));
                            ctx.close();
                        }
                    });
        } catch (Exception e) {
            ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_REFUSED_NOT_AUTHORIZED));
            ctx.close();
        }
    }

    private X509Certificate getX509Certificate() {
        try {
            X509Certificate[] certChain = sslHandler.engine().getSession().getPeerCertificateChain();
            if (certChain.length > 0) {
                return certChain[0];
            }
        } catch (SSLPeerUnverifiedException e) {
            log.warn(e.getMessage());
            return null;
        }
        return null;
    }

    private void processDisconnect(ChannelHandlerContext ctx) {
        ctx.close();
        log.info("[{}] Client disconnected!", sessionId);
        if (deviceSessionCtx.isConnected()) {
            transportService.process(sessionInfo, AbstractTransportService.getSessionEventMsg(SessionEvent.CLOSED), null);
            transportService.deregisterSession(sessionInfo);
            if (gatewaySessionHandler != null) {
                gatewaySessionHandler.onGatewayDisconnect();
            }
        }
    }

    private MqttConnAckMessage createMqttConnAckMsg(MqttConnectReturnCode returnCode) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(CONNACK, false, AT_MOST_ONCE, false, 0);
        MqttConnAckVariableHeader mqttConnAckVariableHeader =
                new MqttConnAckVariableHeader(returnCode, true);
        return new MqttConnAckMessage(mqttFixedHeader, mqttConnAckVariableHeader);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[{}] Unexpected Exception", sessionId, cause);
        ctx.close();
    }

    private static MqttSubAckMessage createSubAckMessage(Integer msgId, List<Integer> grantedQoSList) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(SUBACK, false, AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader mqttMessageIdVariableHeader = MqttMessageIdVariableHeader.from(msgId);
        MqttSubAckPayload mqttSubAckPayload = new MqttSubAckPayload(grantedQoSList);
        return new MqttSubAckMessage(mqttFixedHeader, mqttMessageIdVariableHeader, mqttSubAckPayload);
    }

    private static int getMinSupportedQos(MqttQoS reqQoS) {
        return Math.min(reqQoS.value(), MAX_SUPPORTED_QOS_LVL.value());
    }

    public static MqttPubAckMessage createMqttPubAckMsg(int requestId) {
        MqttFixedHeader mqttFixedHeader =
                new MqttFixedHeader(PUBACK, false, AT_LEAST_ONCE, false, 0);
        MqttMessageIdVariableHeader mqttMsgIdVariableHeader =
                MqttMessageIdVariableHeader.from(requestId);
        return new MqttPubAckMessage(mqttFixedHeader, mqttMsgIdVariableHeader);
    }

    private boolean checkConnected(ChannelHandlerContext ctx, MqttMessage msg) {
        if (deviceSessionCtx.isConnected()) {
            return true;
        } else {
            log.info("[{}] Closing current session due to invalid msg order: {}", sessionId, msg);
            ctx.close();
            return false;
        }
    }

    private void checkGatewaySession() {
        DeviceInfoProto device = deviceSessionCtx.getDeviceInfo();
        try {
            JsonNode infoNode = context.getMapper().readTree(device.getAdditionalInfo());
            if (infoNode != null) {
                JsonNode gatewayNode = infoNode.get("gateway");
                if (gatewayNode != null && gatewayNode.asBoolean()) {
                    gatewaySessionHandler = new GatewaySessionHandler(context, deviceSessionCtx, sessionId);
                }
            }
        } catch (IOException e) {
            log.trace("[{}][{}] Failed to fetch device additional info", sessionId, device.getDeviceName(), e);
        }
    }

    @Override
    public void operationComplete(Future<? super Void> future) throws Exception {
        if (deviceSessionCtx.isConnected()) {
            transportService.process(sessionInfo, AbstractTransportService.getSessionEventMsg(SessionEvent.CLOSED), null);
            transportService.deregisterSession(sessionInfo);
        }
    }

    /**
     * 设备成功连接后的回调函数
     * - 设备信息（deviceInfo）放入设备会话中（deviceSessionCtx）
     * - 实例化会话信息（sessionInfo，注意区别deviceSessionCtx）
     * -
     * @param msg
     * @param ctx
     */
    private void onValidateDeviceResponse(ValidateDeviceCredentialsResponseMsg msg, ChannelHandlerContext ctx) {
        if (!msg.hasDeviceInfo()) {
            ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_REFUSED_NOT_AUTHORIZED));
            ctx.close();
        } else {
            deviceSessionCtx.setDeviceInfo(msg.getDeviceInfo());
            sessionInfo = SessionInfoProto.newBuilder()
                    .setNodeId(context.getNodeId())
                    .setSessionIdMSB(sessionId.getMostSignificantBits())
                    .setSessionIdLSB(sessionId.getLeastSignificantBits())
                    .setDeviceIdMSB(msg.getDeviceInfo().getDeviceIdMSB())
                    .setDeviceIdLSB(msg.getDeviceInfo().getDeviceIdLSB())
                    .setTenantIdMSB(msg.getDeviceInfo().getTenantIdMSB())
                    .setTenantIdLSB(msg.getDeviceInfo().getTenantIdLSB())
                    .build();
            transportService.process(sessionInfo, AbstractTransportService.getSessionEventMsg(SessionEvent.OPEN), null);
            transportService.registerAsyncSession(sessionInfo, this);
            checkGatewaySession();
            ctx.writeAndFlush(createMqttConnAckMsg(CONNECTION_ACCEPTED));
            log.info("[{}] Client connected!", sessionId);
        }
    }

    @Override
    public void onGetAttributesResponse(TransportProtos.GetAttributeResponseMsg response) {
        try {
            adaptor.convertToPublish(deviceSessionCtx, response).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device attributes response to MQTT msg", sessionId, e);
        }
    }

    @Override
    public void onAttributeUpdate(TransportProtos.AttributeUpdateNotificationMsg notification) {
        try {
            adaptor.convertToPublish(deviceSessionCtx, notification).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device attributes update to MQTT msg", sessionId, e);
        }
    }

    /**
     * 会话过期，自动断开设备连接
     * @param sessionCloseNotification
     */
    @Override
    public void onRemoteSessionCloseCommand(TransportProtos.SessionCloseNotificationProto sessionCloseNotification) {
        log.trace("[{}] Received the remote command to close the session", sessionId);
        processDisconnect(deviceSessionCtx.getChannel());
    }

    @Override
    public void onToDeviceRpcRequest(TransportProtos.ToDeviceRpcRequestMsg rpcRequest) {
        log.trace("[{}] Received RPC command to device", sessionId);
        try {
            adaptor.convertToPublish(deviceSessionCtx, rpcRequest).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device RPC commandto MQTT msg", sessionId, e);
        }
    }

    @Override
    public void onToServerRpcResponse(TransportProtos.ToServerRpcResponseMsg rpcResponse) {
        log.trace("[{}] Received RPC command to device", sessionId);
        try {
            adaptor.convertToPublish(deviceSessionCtx, rpcResponse).ifPresent(deviceSessionCtx.getChannel()::writeAndFlush);
        } catch (Exception e) {
            log.trace("[{}] Failed to convert device RPC commandto MQTT msg", sessionId, e);
        }
    }
}
