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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 读取thingsboard.yml文件audit-log.logging-level的审计级别：关闭OFF，W写，RW读写
 * # Allowed values: OFF (disable), W (log write operations), RW (log read and write operations)
 *   logging-level:
 *     mask:
 *       "device": "${AUDIT_LOG_MASK_DEVICE:W}"
 *       "asset": "${AUDIT_LOG_MASK_ASSET:W}"
 *       "dashboard": "${AUDIT_LOG_MASK_DASHBOARD:W}"
 *       "customer": "${AUDIT_LOG_MASK_CUSTOMER:W}"
 *       "user": "${AUDIT_LOG_MASK_USER:W}"
 *       "rule_chain": "${AUDIT_LOG_MASK_RULE_CHAIN:W}"
 *       "alarm": "${AUDIT_LOG_MASK_ALARM:W}"
 *       "entity_view": "${AUDIT_LOG_MASK_ENTITY_VIEW:W}"
 */
@Configuration
@ConfigurationProperties(prefix = "audit-log.logging-level")
public class AuditLogLevelProperties {

    private Map<String, String> mask = new HashMap<>();

    public AuditLogLevelProperties() {
        super();
    }

    public void setMask(Map<String, String> mask) {
        this.mask = mask;
    }

    public Map<String, String> getMask() {
        return this.mask;
    }
}
