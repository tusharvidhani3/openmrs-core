/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.api.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.openmrs.api.context.ServiceContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ListFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
@ComponentScan(basePackages = { "org.openmrs.api", "org.openmrs.layout" })
public class TestingApplicationContextConfig {

    @Bean
    public ServiceContext serviceContextChild(@Qualifier("serviceContext") ServiceContext parent) {
        parent.setUseSystemClassLoader(true);
        return parent;
    }

    @Bean
    public ListFactoryBean moduleTestingMappingJarLocations() {
        ListFactoryBean factoryBean = new ListFactoryBean();
        factoryBean.setSourceList(new ArrayList<>());
        return factoryBean;
    }

    @Bean
    public ListFactoryBean proj001MappingResources() throws Exception {
        ListFactoryBean factoryBean = new ListFactoryBean();
        factoryBean.setSourceList(Arrays.asList("classpath*:*.omod"));
        return factoryBean;
    }

    @Bean
    public ListFactoryBean mappingJarResources(@Qualifier("proj001MappingResources") List<?> parentList) {
        ListFactoryBean factoryBean = new ListFactoryBean();
        factoryBean.setSourceList(new ArrayList<>(parentList));
        return factoryBean;
    }

    static {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:openmrs;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

}
