/*
 * Copyright 2016 KairosDB Authors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.kairosdb.core;

import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.Matchers;
import com.google.inject.name.Names;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;
import org.kairosdb.core.aggregator.*;
import org.kairosdb.core.configuration.ConfigurationTypeListener;
import org.kairosdb.core.datapoints.*;
import org.kairosdb.core.datastore.GuiceQueryPluginFactory;
import org.kairosdb.core.datastore.KairosDatastore;
import org.kairosdb.core.datastore.QueryPluginFactory;
import org.kairosdb.core.datastore.QueryQueuingManager;
import org.kairosdb.core.groupby.*;
import org.kairosdb.core.http.rest.GuiceQueryPreProcessor;
import org.kairosdb.core.http.rest.QueryPreProcessorContainer;
import org.kairosdb.core.http.rest.json.QueryParser;
import org.kairosdb.core.jobs.CacheFileCleaner;
import org.kairosdb.core.processingstage.FeatureProcessingFactory;
import org.kairosdb.core.processingstage.FeatureProcessor;
import org.kairosdb.core.queue.DataPointEventSerializer;
import org.kairosdb.core.queue.QueueProcessor;
import org.kairosdb.core.scheduler.KairosDBScheduler;
import org.kairosdb.core.scheduler.KairosDBSchedulerImpl;
import org.kairosdb.eventbus.EventBusConfiguration;
import org.kairosdb.eventbus.FilterEventBus;
import org.kairosdb.plugin.Aggregator;
import org.kairosdb.plugin.GroupBy;
import org.kairosdb.sample.SampleQueryPlugin;
import org.kairosdb.util.IngestExecutorService;
import org.kairosdb.util.MemoryMonitor;
import org.kairosdb.util.SimpleStatsReporter;
import org.kairosdb.util.Util;
import org.kairosdb.bigqueue.BigArrayImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.kairosdb.bigqueue.IBigArray;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.MissingResourceException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.kairosdb.core.queue.QueueProcessor.QUEUE_PROCESSOR;
import static org.kairosdb.core.queue.QueueProcessor.QUEUE_PROCESSOR_CLASS;


public class CoreModule extends AbstractModule
{
	public static final Logger logger = LoggerFactory.getLogger(CoreModule.class);

	public static final String QUEUE_PATH = "kairosdb.queue_processor.queue_path";
	public static final String PAGE_SIZE = "kairosdb.queue_processor.page_size";

	public static final String DATAPOINTS_FACTORY_LONG = "kairosdb.datapoints.factory.long";
	public static final String DATAPOINTS_FACTORY_DOUBLE = "kairosdb.datapoints.factory.double";

	private final FilterEventBus m_eventBus;
	private KairosRootConfig m_config;

	public CoreModule(KairosRootConfig config)
	{
		m_config = config;
		m_eventBus = new FilterEventBus(new EventBusConfiguration(m_config));
	}

	@SuppressWarnings("rawtypes")
	private Class getClassForProperty(String property)
	{
		String className = m_config.getProperty(property);

		Class klass;
		try
		{
			klass = getClass().getClassLoader().loadClass(className);
		}
		catch (ClassNotFoundException e)
		{
			throw new MissingResourceException("Unable to load class", className, property);
		}

		return (klass);
	}

	public static void bindConfiguration(KairosRootConfig rootConfig, Binder binder)
	{
		binder = binder.skipSources(Names.class);

		Config config = rootConfig.getRawConfig();
		for (String propertyName : rootConfig)
		{
			ConfigValue value = config.getValue(propertyName);

			ConfigValueType configValueType = value.valueType();

			try
			{
				logger.debug(String.format("%s (%s) = %s", propertyName, configValueType.name(), value.unwrapped().toString()));

				//type binding didn't work well for numbers, guice will not convert double to int
				//So we bind everything as a string and let guice convert - which it does well
				if (configValueType == ConfigValueType.LIST)
				{
					try
					{
						List<String> stringList = config.getStringList(propertyName);
						binder.bind(new TypeLiteral<List<String>>() {})
								.annotatedWith(Names.named(propertyName)).toInstance(stringList);
					}
					catch (ConfigException ce)
					{
						logger.debug("Property {} is not a list of string", propertyName);
					}
				}
				else
					binder.bindConstant().annotatedWith(Names.named(propertyName)).to(value.unwrapped().toString());

				binder.bind(ConfigValue.class).annotatedWith(Names.named(propertyName)).toInstance(value);
			}
			catch (Exception e)
			{
				System.out.println("Failed to bind property "+propertyName);
				e.printStackTrace();
			}
		}

		binder.bindListener(Matchers.any(), new ConfigurationTypeListener(rootConfig));
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void configure()
	{
		/*
		This bit of magic makes it so any object that is bound through guice just
		needs to annotate a method with @Subscribe and they can get events.
		 */
		bind(FilterEventBus.class).toInstance(m_eventBus);
		//bind(EventBus.class).toInstance(m_eventBus);
		//Need to register an exception handler
		bindListener(Matchers.any(), new TypeListener()
		{
			public <I> void hear(TypeLiteral<I> typeLiteral, TypeEncounter<I> typeEncounter)
			{
				typeEncounter.register(new InjectionListener<I>()
				{
					public void afterInjection(I i)
					{
						m_eventBus.register(i);
						if (i instanceof KairosPostConstructInit)
						{
							((KairosPostConstructInit)i).init();
						}
					}
				});
			}
		});

		//Names.bindProperties(binder(), m_config);
		bindConfiguration(m_config, binder());
		bind(KairosRootConfig.class).toInstance(m_config);

		bind(QueryQueuingManager.class).in(Singleton.class);
		bind(KairosDatastore.class).in(Singleton.class);

		bind(new TypeLiteral<FeatureProcessingFactory<Aggregator>>() {}).to(AggregatorFactory.class).in(Singleton.class);
		bind(new TypeLiteral<FeatureProcessingFactory<GroupBy>>() {}).to(GroupByFactory.class).in(Singleton.class);

		bind(FeatureProcessor.class).to(KairosFeatureProcessor.class).in(Singleton.class);

		bind(QueryPluginFactory.class).to(GuiceQueryPluginFactory.class).in(Singleton.class);
		bind(QueryParser.class).in(Singleton.class);
		bind(CacheFileCleaner.class).in(Singleton.class);
		bind(KairosDBScheduler.class).to(KairosDBSchedulerImpl.class).in(Singleton.class);
		bind(KairosDBSchedulerImpl.class).in(Singleton.class);
		bind(MemoryMonitor.class).in(Singleton.class);
		bind(DataPointEventSerializer.class).in(Singleton.class);
		bind(SimpleStatsReporter.class);

		bind(SumAggregator.class);
		bind(MinAggregator.class);
		bind(MaxAggregator.class);
		bind(AvgAggregator.class);
		bind(StdAggregator.class);
		bind(RateAggregator.class);
		bind(SamplerAggregator.class);
		bind(LeastSquaresAggregator.class);
		bind(PercentileAggregator.class);
		bind(DivideAggregator.class);
		bind(ScaleAggregator.class);
		bind(CountAggregator.class);
		bind(DiffAggregator.class);
		bind(DataGapsMarkingAggregator.class);
		bind(FirstAggregator.class);
		bind(LastAggregator.class);
		bind(SaveAsAggregator.class);
		bind(TrimAggregator.class);
		bind(SmaAggregator.class);
		bind(FilterAggregator.class);
		bind(ScoreAggregator.class);

		bind(ValueGroupBy.class);
		bind(TimeGroupBy.class);
		bind(TagGroupBy.class);
		bind(BinGroupBy.class);

		String hostname = m_config.getProperty("kairosdb.hostname");
		bindConstant().annotatedWith(Names.named("HOSTNAME")).to(hostname != null ? hostname: Util.getHostName());

		//bind queue processor impl
		//Have to bind the class directly so metrics reporter can get metrics off of them
		bind(getClassForProperty(QUEUE_PROCESSOR_CLASS)).in(Singleton.class);
		bind(QueueProcessor.class)
				.to(getClassForProperty(QUEUE_PROCESSOR_CLASS)).in(Singleton.class);

		//bind datapoint default impls
		bind(DoubleDataPointFactory.class)
				.to(getClassForProperty(DATAPOINTS_FACTORY_DOUBLE)).in(Singleton.class);
		//This is required in case someone overwrites our factory property
		bind(DoubleDataPointFactoryImpl.class).in(Singleton.class);

		bind(LongDataPointFactory.class)
				.to(getClassForProperty(DATAPOINTS_FACTORY_LONG)).in(Singleton.class);
		//This is required in case someone overwrites our factory property
		bind(LongDataPointFactoryImpl.class).in(Singleton.class);

		bind(LegacyDataPointFactory.class).in(Singleton.class);

		bind(StringDataPointFactory.class).in(Singleton.class);

		bind(NullDataPointFactory.class).in(Singleton.class);

		bind(KairosDataPointFactory.class).to(GuiceKairosDataPointFactory.class).in(Singleton.class);

		bind(IngestExecutorService.class);

		bind(HostManager.class).in(Singleton.class);

		String hostIp = m_config.getProperty("kairosdb.host_ip");

		bindConstant().annotatedWith(Names.named("HOST_IP")).to(hostIp != null ? hostIp: InetAddresses.toAddrString(Util.findPublicIp()));

		bind(QueryPreProcessorContainer.class).to(GuiceQueryPreProcessor.class).in(Singleton.class);

		bind(SampleQueryPlugin.class);
	}

	@Provides
	@Singleton
	public IBigArray getBigArray(@Named(QUEUE_PATH) String queuePath,
			@Named(PAGE_SIZE) int pageSize) throws IOException
	{
		return new BigArrayImpl(queuePath, "kairos_queue", pageSize);
	}

	@Provides @Named(QUEUE_PROCESSOR) @Singleton
	public ExecutorService getQueueExecutor()
	{
		return Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("QueueProcessor-%s").build());
	}

	@Provides
	@Singleton
	@Named(HostManager.HOST_MANAGER_SERVICE_EXECUTOR)
	public ScheduledExecutorService getExecutorService()
	{
		return Executors.newSingleThreadScheduledExecutor(
				new ThreadFactoryBuilder().setNameFormat("HostManagerService-%s").build());

	}
}
