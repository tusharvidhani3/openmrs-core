/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.api;

import ca.uhn.hl7v2.app.Application;
import ca.uhn.hl7v2.app.MessageTypeRouter;
import ca.uhn.hl7v2.parser.GenericParser;
import org.hibernate.SessionFactory;
import org.openmrs.annotation.Handler;
import org.openmrs.annotation.OpenmrsProfileExcludeFilter;
import org.openmrs.annotation.OpenmrsProfileIncludeFilter;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ServiceContext;
import org.openmrs.api.db.ContextDAO;
import org.openmrs.api.db.hibernate.HibernateSessionFactoryBean;
import org.openmrs.api.impl.AdministrationServiceImpl;
import org.openmrs.api.impl.GlobalLocaleList;
import org.openmrs.api.impl.OrderServiceImpl;
import org.openmrs.api.impl.PersonNameGlobalPropertyListener;
import org.openmrs.hl7.HL7Service;
import org.openmrs.hl7.handler.ADTA28Handler;
import org.openmrs.hl7.handler.ORUR01Handler;
import org.openmrs.logging.LoggingConfigurationGlobalPropertyListener;
import org.openmrs.messagesource.MessageSourceService;
import org.openmrs.messagesource.impl.MutableResourceBundleMessageSource;
import org.openmrs.notification.AlertService;
import org.openmrs.notification.MessageService;
import org.openmrs.obs.ComplexObsHandler;
import org.openmrs.obs.handler.BinaryDataHandler;
import org.openmrs.obs.handler.BinaryStreamHandler;
import org.openmrs.obs.handler.ImageHandler;
import org.openmrs.obs.handler.TextHandler;
import org.openmrs.patient.IdentifierValidator;
import org.openmrs.patient.impl.LuhnIdentifierValidator;
import org.openmrs.patient.impl.VerhoeffIdentifierValidator;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.serialization.OpenmrsSerializer;
import org.openmrs.serialization.SimpleXStreamSerializer;
import org.openmrs.util.ConfigUtil;
import org.openmrs.util.HttpClient;
import org.openmrs.util.LocaleUtility;
import org.openmrs.util.LocationUtility;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.util.TestTypeFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.hibernate5.HibernateTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

/**
 * Provides OpenMRS Application Context Spring Configuration.
 * 
 * It's a replacement for applicationContext-service.xml from which we gradually
 * migrate away.
 * 
 * @see org.openmrs.aop.AOPConfig
 * @see org.openmrs.api.cache.CacheConfig
 * 
 * @since 3.0.0
 */
@Configuration
@ComponentScan(basePackages = "org.openmrs", includeFilters = {
		@ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Handler.class),
		@ComponentScan.Filter(type = FilterType.CUSTOM, classes = OpenmrsProfileIncludeFilter.class)
}, excludeFilters = {
		@ComponentScan.Filter(type = FilterType.CUSTOM, classes = TestTypeFilter.class),
		@ComponentScan.Filter(type = FilterType.CUSTOM, classes = OpenmrsProfileExcludeFilter.class)
})
@EnableTransactionManagement
public class OpenmrsApplicationContextConfig {

	@Bean
	public PlatformTransactionManager transactionManager(SessionFactory sessionFactory) {
		return new HibernateTransactionManager(sessionFactory);
	}

	@Bean
	public TaskExecutor taskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(20);
		executor.setQueueCapacity(1000);
		return executor;
	}

	@Bean
	public Map<String, ComplexObsHandler> handlers(ImageHandler imageHandler, TextHandler textHandler,
			BinaryDataHandler binaryDataHandler,
			BinaryStreamHandler binaryStreamHandler) {
		Map<String, ComplexObsHandler> map = new LinkedHashMap<>();
		map.put("ImageHandler", imageHandler);
		map.put("TextHandler", textHandler);
		map.put("BinaryDataHandler", binaryDataHandler);
		map.put("BinaryStreamHandler", binaryStreamHandler);
		return map;
	}

	@Bean
	public Map<Class<?>, IdentifierValidator> identifierValidators(LuhnIdentifierValidator luhnIdentifierValidator,
			VerhoeffIdentifierValidator verhoeffIdentifierValidator) {
		Map<Class<?>, IdentifierValidator> map = new LinkedHashMap<>();
		map.put(LuhnIdentifierValidator.class, luhnIdentifierValidator);
		map.put(VerhoeffIdentifierValidator.class, verhoeffIdentifierValidator);
		return map;
	}

	@Bean
	public List<OpenmrsSerializer> serializerList(SimpleXStreamSerializer simpleXStreamSerializer) {
		List<OpenmrsSerializer> serializers = new ArrayList<>();
		serializers.add(simpleXStreamSerializer);
		return serializers;
	}

	@Bean
	public MutableResourceBundleMessageSource mutableResourceBundleMessageSource() {
		MutableResourceBundleMessageSource messageSource = new MutableResourceBundleMessageSource();
		messageSource.setBasenames("classpath:custom_messages", "classpath:messages");
		messageSource.setUseCodeAsDefaultMessage(true);
		messageSource.setCacheSeconds(5);
		messageSource.setDefaultEncoding("UTF-8");
		return messageSource;
	}

	/**
	 * Provides a PropertySourcesPlaceholderConfigurer that uses
	 * OpenmrsUtil.getApplicationDataDirectory()
	 * to resolve the runtime properties file location, ensuring the property is
	 * always set.
	 * 
	 * @return configurer
	 */
	@Bean
	public PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
		PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
		String appDataDir = OpenmrsUtil.getApplicationDataDirectory();
		Properties props = new Properties();
		props.setProperty(OpenmrsConstants.KEY_OPENMRS_APPLICATION_DATA_DIRECTORY, appDataDir);
		configurer.setProperties(props);
		configurer.setLocations(new ClassPathResource("hibernate.default.properties"),
				new FileSystemResource(appDataDir + "/openmrs-runtime.properties"));
		configurer.setIgnoreResourceNotFound(true);
		configurer.setLocalOverride(true);
		return configurer;
	}

	@Bean
	public GenericParser hL7Parser() {
		return new GenericParser();
	}

	@Bean
	public MessageTypeRouter hL7Router() {
		return new MessageTypeRouter();
	}

	@Bean
	public Map<String, Application> hL7Handlers(ORUR01Handler orur01Handler, ADTA28Handler adta28Handler) {
		Map<String, Application> map = new LinkedHashMap<>();
		map.put("ORU_R01", orur01Handler);
		map.put("ADT_A28", adta28Handler);
		return map;
	}

	@Bean(destroyMethod = "destroyInstance")
	public ServiceContext serviceContext(ApplicationContext applicationContext) {
		ServiceContext serviceContext = ServiceContext.getInstance();
		serviceContext.setApplicationContext(applicationContext);

		serviceContext.setPatientService(applicationContext.getBean(PatientService.class));
		serviceContext.setPersonService(applicationContext.getBean(PersonService.class));
		serviceContext.setConceptService(applicationContext.getBean(ConceptService.class));
		serviceContext.setUserService(applicationContext.getBean(UserService.class));
		serviceContext.setObsService(applicationContext.getBean(ObsService.class));
		serviceContext.setEncounterService(applicationContext.getBean(EncounterService.class));
		serviceContext.setLocationService(applicationContext.getBean(LocationService.class));
		serviceContext.setOrderService(applicationContext.getBean(OrderService.class));
		serviceContext.setConditionService(applicationContext.getBean(ConditionService.class));
		serviceContext.setDiagnosisService(applicationContext.getBean(DiagnosisService.class));
		serviceContext.setMedicationDispenseService(applicationContext.getBean(MedicationDispenseService.class));
		serviceContext.setOrderSetService(applicationContext.getBean(OrderSetService.class));
		serviceContext.setFormService(applicationContext.getBean(FormService.class));
		serviceContext.setAdministrationService(applicationContext.getBean(AdministrationService.class));
		serviceContext.setDatatypeService(applicationContext.getBean(DatatypeService.class));
		serviceContext.setProgramWorkflowService(applicationContext.getBean(ProgramWorkflowService.class));
		serviceContext.setCohortService(applicationContext.getBean(CohortService.class));
		serviceContext.setMessageService(applicationContext.getBean(MessageService.class));
		serviceContext.setSerializationService(applicationContext.getBean(SerializationService.class));
		serviceContext.setSchedulerService(applicationContext.getBean(SchedulerService.class));
		serviceContext.setAlertService(applicationContext.getBean(AlertService.class));
		serviceContext.setHl7Service(applicationContext.getBean(HL7Service.class));
		serviceContext.setMessageSourceService(applicationContext.getBean(MessageSourceService.class));
		serviceContext.setVisitService(applicationContext.getBean(VisitService.class));
		serviceContext.setProviderService(applicationContext.getBean(ProviderService.class));

		return serviceContext;
	}

	@Bean(initMethod = "setAuthenticationScheme")
	public Context context(@Qualifier("serviceContext") ServiceContext serviceContext, ContextDAO contextDAO) {
		Context ctx = new Context();
		ctx.setServiceContext(serviceContext);
		ctx.setContextDAO(contextDAO);

		return ctx;
	}

	@Bean
	public DataSource dataSource() {

		Properties props = new Properties();
    try (InputStream input = getClass().getClassLoader()
            .getResourceAsStream("hibernate.default.properties")) {
        props.load(input);
    } catch (IOException e) {
        throw new RuntimeException("Failed to load hibernate-default.properties", e);
    }

		DriverManagerDataSource ds = new DriverManagerDataSource();
		ds.setDriverClassName(props.getProperty("hibernate.connection.driver_class"));
		ds.setUrl(props.getProperty("hibernate.connection.url"));
		ds.setUsername(props.getProperty("hibernate.connection.username"));
		ds.setPassword(props.getProperty("hibernate.connection.password"));
		return ds;
	}

	@Bean
	public HibernateSessionFactoryBean sessionFactory(Resource[] mappingJarResources, DataSource dataSource) {
		HibernateSessionFactoryBean sessionFactory = new HibernateSessionFactoryBean();
		sessionFactory.setDataSource(dataSource);
		sessionFactory.setConfigLocations(new Resource[] {
				new ClassPathResource("hibernate.cfg.xml")
		});
		sessionFactory.setMappingJarLocations(mappingJarResources);
		sessionFactory.setPackagesToScan("org.openmrs");
		return sessionFactory;
	}

	@Bean
	public HttpClient implementationIdHttpClient() throws MalformedURLException {
		HttpClient httpClient = new HttpClient("https://implementation.openmrs.org");
		return httpClient;
	}

	@Bean
	public List<Resource> moduleTestingMappingJarLocations() {
		return new ArrayList<>();
	}

	@Bean
	public Resource[] mappingJarResources(List<Resource> moduleTestingMappingJarLocations) {
		List<Resource> merged = new ArrayList<>(moduleTestingMappingJarLocations);
		return merged.toArray(new Resource[0]);
	}

	@Bean
	public EventListeners openmrsEventListeners(LocaleUtility localeUtility,
			LocationUtility locationUtility,
			ConfigUtil configUtilGlobalPropertyListener,
			PersonNameGlobalPropertyListener personNameGlobalPropertyListener,
			LoggingConfigurationGlobalPropertyListener loggingConfigurationGlobalPropertyListener,
			GlobalLocaleList globalLocaleList,
			AdministrationServiceImpl adminService,
			OrderServiceImpl orderService) {
		EventListeners listeners = new EventListeners();
		listeners.setGlobalPropertyListenersToEmpty(false);

		List<GlobalPropertyListener> list = new ArrayList<>();
		list.add(localeUtility);
		list.add(locationUtility);
		list.add(configUtilGlobalPropertyListener);
		list.add(personNameGlobalPropertyListener);
		list.add(loggingConfigurationGlobalPropertyListener);
		list.add(globalLocaleList);
		list.add(adminService);
		list.add(orderService);

		listeners.setGlobalPropertyListeners(list);
		return listeners;
	}

}
