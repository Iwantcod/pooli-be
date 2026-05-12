package com.pooli.monitoring.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficRedisAlertRuleConfigTest {

    @Test
    @DisplayName("Prometheus rule contains EM6 Redis failure thresholds and min request guard")
    void prometheusRuleContainsRedisFailureThresholds() throws IOException {
        String rules = Files.readString(Path.of("deploy/monitoring/rules/traffic-redis-alerts.yml"));

        assertThat(rules).contains(
                "TrafficRedisWarningFailureRate",
                "TrafficRedisCriticalFailureRate",
                "TrafficRedisGlobalDown",
                "{kind=~\"timeout|connection\"}",
                ">= 0.10",
                ">= 0.30",
                ">= 0.70",
                "traffic_redis_ping_failures >= 5",
                "increase(traffic_redis_ops_total[1m])) >= 100"
        );
    }

    @Test
    @DisplayName("Alertmanager uses webhook_url_file for Discord secret")
    void alertmanagerUsesWebhookUrlFile() throws IOException {
        String alertmanager = Files.readString(Path.of("deploy/monitoring/alertmanager.yml"));

        assertThat(alertmanager).contains(
                "discord_configs:",
                "webhook_url_file: /etc/alertmanager/secrets/discord_webhook_url"
        );
    }

    @Test
    @DisplayName("Prometheus compose is separated from the Spring app compose")
    void prometheusComposeIsSeparatedFromSpringAppCompose() throws IOException {
        String rootCompose = Files.readString(Path.of("docker-compose.yml"));
        String monitoringCompose = Files.readString(Path.of("deploy/monitoring/docker-compose.yml"));

        assertThat(rootCompose)
                .contains("app:")
                .doesNotContain("prometheus:")
                .doesNotContain("alertmanager:");
        assertThat(monitoringCompose).contains(
                "prometheus:",
                "image: prom/prometheus:latest",
                "container_name: local-prometheus",
                "mem_limit: 512m",
                "cpus: 0.50",
                "prometheus-data:/prometheus",
                "--storage.tsdb.retention.time=2d",
                "--web.enable-lifecycle",
                "alertmanager:"
        );
    }
}
