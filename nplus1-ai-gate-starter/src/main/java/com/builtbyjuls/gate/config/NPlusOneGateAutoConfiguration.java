package com.builtbyjuls.gate.config;

import com.builtbyjuls.gate.interceptor.NPlusOneQueryExecutionListener;
import com.builtbyjuls.gate.report.NPlusOneReportExporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Spring Boot auto-configuration for N+1 query gate datasource interception.
 */
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(NPlusOneGateProperties.class)
public class NPlusOneGateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NPlusOneQueryExecutionListener nPlusOneQueryExecutionListener() {
        return new NPlusOneQueryExecutionListener();
    }

    @Bean
    @ConditionalOnMissingBean
    public NPlusOneDataSourceBeanPostProcessor nPlusOneDataSourceBeanPostProcessor(
            NPlusOneQueryExecutionListener nPlusOneQueryExecutionListener
    ) {
        return new NPlusOneDataSourceBeanPostProcessor(nPlusOneQueryExecutionListener);
    }

    @Bean
    @ConditionalOnMissingBean
    public NPlusOneReportExporter nPlusOneReportExporter() {
        return new NPlusOneReportExporter();
    }
}
