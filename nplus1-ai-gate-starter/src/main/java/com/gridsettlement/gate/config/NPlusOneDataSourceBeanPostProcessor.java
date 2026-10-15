package com.gridsettlement.gate.config;

import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Spring BeanPostProcessor that wraps candidate DataSource beans with datasource-proxy
 * and registers the NPlusOneQueryExecutionListener.
 */
public class NPlusOneDataSourceBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private final QueryExecutionListener queryExecutionListener;

    public NPlusOneDataSourceBeanPostProcessor(QueryExecutionListener queryExecutionListener) {
        this.queryExecutionListener = Objects.requireNonNull(queryExecutionListener, "queryExecutionListener must not be null");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && !(bean instanceof ProxyDataSource)) {
            return ProxyDataSourceBuilder.create(dataSource)
                    .name("NPlusOneProxyDataSource")
                    .listener(queryExecutionListener)
                    .build();
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
