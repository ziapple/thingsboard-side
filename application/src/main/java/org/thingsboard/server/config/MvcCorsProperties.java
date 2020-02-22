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
import org.springframework.web.cors.CorsConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于cors协议实现跨域请求，/api/**所有请求都支持跨域访问
 * # spring CORS configuration
 * spring.mvc.cors:
 *    mappings:
 *      # Intercept path
 *       "[/api/**]":
 *          #Comma-separated list of origins to allow. '*' allows all origins. When not set,CORS support is disabled.
 *          allowed-origins: "*"
 *          #Comma-separated list of methods to allow. '*' allows all methods.
 *          allowed-methods: "*"
 *          #Comma-separated list of headers to allow in a request. '*' allows all headers.
 *          allowed-headers: "*"
 *          #How long, in seconds, the response from a pre-flight request can be cached by clients.
 *          max-age: "1800"
 *          #Set whether credentials are supported. When not set, credentials are not supported.
 *          allow-credentials: "true"
 */
@Configuration
@ConfigurationProperties(prefix = "spring.mvc.cors")
public class MvcCorsProperties {

    private Map<String, CorsConfiguration> mappings = new HashMap<>();

    public MvcCorsProperties() {
        super();
    }

    public Map<String, CorsConfiguration> getMappings() {
        return mappings;
    }

    public void setMappings(Map<String, CorsConfiguration> mappings) {
        this.mappings = mappings;
    }
}
