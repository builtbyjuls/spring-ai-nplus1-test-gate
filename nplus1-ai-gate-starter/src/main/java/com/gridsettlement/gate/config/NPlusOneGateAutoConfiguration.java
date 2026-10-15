package com.gridsettlement.gate.config;

import com.gridsettlement.gate.interceptor.NPlusOneQueryExecutionListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Spring Boot auto-configuration for N+1 query gate datasource interception.
 */
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
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
}
