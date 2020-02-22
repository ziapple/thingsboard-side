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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.tools.TbRateLimits;
import org.thingsboard.server.common.msg.tools.TbRateLimitsException;
import org.thingsboard.server.exception.ThingsboardErrorResponseHandler;
import org.thingsboard.server.service.security.model.SecurityUser;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 服务请求流量限制，对租户tenant和客户custom进行限制（注意不是请求限制），tenant，custom都是user的集合，默认不开启
 * 采用bucket4j漏桶(Leaky Bucket)算法实现{@link org.thingsboard.server.common.msg.tools.TbRateLimits}
 * 水(请求)先进入到漏桶里,出水口流出(接口有响应速率)
 * 当水流入速度过大会直接溢出(访问频率超过接口响应速率),然后就拒绝请求
 * 100:1,100表示桶的容量（最大处理请求数量）capacity，1表示单位时间，表示1秒中租户最大请求数为100
 * 2000:60,表示60秒最大请求数为2000
 * rest:
 *     limits:
 *       tenant:
 *         enabled: "${TB_SERVER_REST_LIMITS_TENANT_ENABLED:false}"
 *         configuration: "${TB_SERVER_REST_LIMITS_TENANT_CONFIGURATION:100:1,2000:60}"
 *       customer:
 *         enabled: "${TB_SERVER_REST_LIMITS_CUSTOMER_ENABLED:false}"
 *         configuration: "${TB_SERVER_REST_LIMITS_CUSTOMER_CONFIGURATION:50:1,1000:60}"
 */
@Component
public class RateLimitProcessingFilter extends GenericFilterBean {
    @Value("${server.rest.limits.tenant.enabled:false}")
    private boolean perTenantLimitsEnabled;
    @Value("${server.rest.limits.tenant.configuration:}")
    private String perTenantLimitsConfiguration;
    @Value("${server.rest.limits.customer.enabled:false}")
    private boolean perCustomerLimitsEnabled;
    @Value("${server.rest.limits.customer.configuration:}")
    private String perCustomerLimitsConfiguration;

    @Autowired
    private ThingsboardErrorResponseHandler errorResponseHandler;

    private ConcurrentMap<TenantId, TbRateLimits> perTenantLimits = new ConcurrentHashMap<>();
    private ConcurrentMap<CustomerId, TbRateLimits> perCustomerLimits = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        SecurityUser user = getCurrentUser();
        if (user != null && !user.isSystemAdmin()) {
            if (perTenantLimitsEnabled) {
                //判断map里租户Id是否为空，为空则map.put(tenantId, TbRateLimits)
                TbRateLimits rateLimits = perTenantLimits.computeIfAbsent(user.getTenantId(), id -> new TbRateLimits(perTenantLimitsConfiguration));
                if (!rateLimits.tryConsume()) {
                    errorResponseHandler.handle(new TbRateLimitsException(EntityType.TENANT), (HttpServletResponse) response);
                    return;
                }
            }
            if (perCustomerLimitsEnabled && user.isCustomerUser()) {
                TbRateLimits rateLimits = perCustomerLimits.computeIfAbsent(user.getCustomerId(), id -> new TbRateLimits(perCustomerLimitsConfiguration));
                if (!rateLimits.tryConsume()) {
                    errorResponseHandler.handle(new TbRateLimitsException(EntityType.CUSTOMER), (HttpServletResponse) response);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    protected SecurityUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SecurityUser) {
            return (SecurityUser) authentication.getPrincipal();
        } else {
            return null;
        }
    }

}
