package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;

/**
 * Elasticsearch 连接配置。
 *
 * 注意：这里不再强制启用 SSL——本环境 ES 为明文 http（docker run 时
 * 关闭了 xpack.security 与 TLS）。uris 必须使用 {@code host:port}（不带
 * scheme），否则 Spring Data Elasticsearch 的 InetSocketAddressParser 会
 * 把整个字符串当成主机名（如 "http://localhost:9200" 报 UnknownHostException）。
 * 若将来 ES 重新开启 TLS/security，再把 usingSsl(...) 与证书加载加回来。
 */
@Configuration
public class ElasticConfiguration extends ElasticsearchConfiguration {

    @Value("${spring.elasticsearch.uris}")
    String[] uris;

    @Value("${spring.elasticsearch.username}")
    String username;

    @Value("${spring.elasticsearch.password}")
    String password;

    @Override
    public ClientConfiguration clientConfiguration() {
        return ClientConfiguration.builder()
                .connectedTo(uris)
                .withBasicAuth(username, password)
                .build();
    }
}
