package org.kairosdb.datastore.cassandra;

import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.exceptions.UnavailableException;
import com.google.common.collect.ImmutableSortedMap;
import com.google.inject.assistedinject.Assisted;
import org.json.JSONWriter;
import org.kairosdb.core.DataPoint;
import org.kairosdb.core.queue.EventCompletionCallBack;
import org.kairosdb.eventbus.FilterEventBus;
import org.kairosdb.eventbus.Publisher;
import org.kairosdb.events.BatchReductionEvent;
import org.kairosdb.events.DataPointEvent;
import org.kairosdb.events.RowKeyEvent;
import org.kairosdb.util.RetryCallable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


/**
 Created by bhawkins on 1/11/17.
 */
public class BatchHandler extends RetryCallable
{
	public static final Logger logger = LoggerFactory.getLogger(BatchHandler.class);
	public static final Logger failedLogger = LoggerFactory.getLogger("failed_logger");

	private final List<DataPointEvent> m_events;
	private final EventCompletionCallBack m_callBack;
	private final int m_defaultTtl;
	private final boolean m_allignDatapointTtl;
	private final boolean m_forceDefaultDatapointTtl;
	private final DataCache<DataPointsRowKey> m_rowKeyCache;
	private final DataCache<TimedString> m_metricNameCache;
	private final CassandraModule.CQLBatchFactory m_cqlBatchFactory;
	private final Publisher<RowKeyEvent> m_rowKeyPublisher;
	private final Publisher<BatchReductionEvent> m_batchReductionPublisher;
	private final String m_clusterName;
	private final RowSpec m_rowSpec;

	@Inject
	public BatchHandler(
			@Assisted List<DataPointEvent> events,
			@Assisted EventCompletionCallBack callBack,
			CassandraConfiguration configuration,
			DataCache<DataPointsRowKey> rowKeyCache,
			DataCache<TimedString> metricNameCache,
			FilterEventBus eventBus,
			CassandraModule.CQLBatchFactory cqlBatchFactory,
			@Assisted RowSpec rowSpec)
	{
		m_events = events;
		m_callBack = callBack;
		m_defaultTtl = configuration.getDatapointTtl();
		m_clusterName = configuration.getWriteCluster().getClusterName();
		m_allignDatapointTtl = configuration.isAlignDatapointTtlWithTimestamp();
		m_forceDefaultDatapointTtl = configuration.isForceDefaultDatapointTtl();
		m_rowKeyCache = rowKeyCache;
		m_metricNameCache = metricNameCache;

		m_cqlBatchFactory = cqlBatchFactory;
		m_rowSpec = rowSpec;

		m_rowKeyPublisher = eventBus.createPublisher(RowKeyEvent.class);
		m_batchReductionPublisher = eventBus.createPublisher(BatchReductionEvent.class);
	}


	private void loadBatch(int limit, CQLBatch batch, Iterator<DataPointEvent> events) throws Exception
	{
		int count = 0;
		while (events.hasNext() && count < limit)
		{
			DataPointEvent event = events.next();
			count++;

			String metricName = event.getMetricName();
			if (metricName.length() == 0)
			{
				logger.warn(
						"Attempted to add empty metric name to string index. Row looks like: " + event.getDataPoint()
				);
			}

			ImmutableSortedMap<String, String> tags = event.getTags();
			DataPoint dataPoint = event.getDataPoint();
			
			// force default ttl if property is set, use event's ttl otherwise
			int ttl = m_forceDefaultDatapointTtl ? m_defaultTtl : event.getTtl();
			logger.trace("ttl (seconds): {}", ttl);

			DataPointsRowKey rowKey = null;
			//time the data is written.
			long writeTime = System.currentTimeMillis();
			
			// set ttl to default if the event's ttl is not present or 0 (which actually can be 0 as well)
			if (0 == ttl)
				ttl = m_defaultTtl;

			// check if datapoint ttl alignment should be used
			if (m_allignDatapointTtl)
			{
				// determine the datapoint's "age" comparing it's timestamp and now
				int datapointAgeInSeconds = (int) ((writeTime - dataPoint.getTimestamp()) / 1000);
				logger.trace("datapointAgeInSeconds: {}", datapointAgeInSeconds);
				
				// the resulting aligned ttl is the former calculated ttl minus the datapoint's age
				ttl = ttl - datapointAgeInSeconds;
				logger.trace("alligned ttl (seconds): {}", ttl);
				// if the aligned ttl is negative, the datapoint is already dead
				if (ttl <= 0)
				{
			        logger.warn("alligned ttl for {} with tags {} is negative, so the datapoint is already dead, no need to store it", metricName, tags);
			        continue;
				}
			}
			

			long rowTime = m_rowSpec.calculateRowTime(dataPoint.getTimestamp());

			rowKey = new DataPointsRowKey(metricName, m_clusterName, rowTime, dataPoint.getDataStoreDataType(),
					tags);

			//Write out the row key if it is not cached
			DataPointsRowKey cachedRowKey = m_rowKeyCache.cacheItem(rowKey);
			if (cachedRowKey == null)
			{
				cachedRowKey = rowKey;

				//Row key will expire using the ttl plus the width of the row (typically 3 weeks)
				int rowKeyTtl = (ttl == 0) ? 0 : ttl + ((int) (m_rowSpec.getRowWidthInMillis() / 1000));

				batch.addRowKey(cachedRowKey, rowKeyTtl);

				String cachedName = cachedRowKey.getMetricName();


				m_rowKeyPublisher.post(new RowKeyEvent(cachedName, cachedRowKey, rowKeyTtl));

				TimedString metricNameTime = new TimedString(cachedName, rowTime);

				TimedString cacheName = m_metricNameCache.cacheItem(metricNameTime);
				if (cacheName == null)
				{
					batch.addMetricName(metricNameTime);
					batch.addTimeIndex(metricNameTime.getString(), cachedRowKey.getTimestamp(), rowKeyTtl);
				}
			}

			int columnTime = m_rowSpec.getColumnName(rowTime, dataPoint.getTimestamp());

			batch.addDataPoint(rowKey, columnTime, dataPoint, ttl);
		}
	}

	private void clearCacheOfFailedBatch(CQLBatch batch)
	{
		if (batch != null)
		{
			for (TimedString newMetric : batch.getNewMetrics())
			{
				m_metricNameCache.removeKey(newMetric);
			}

			for (DataPointsRowKey newRowKey : batch.getNewRowKeys())
			{
				m_rowKeyCache.removeKey(newRowKey);
			}
		}
	}

	@Override
	public void retryCall() throws Exception
	{
		int divisor = 1;
		boolean retry = false;
		int limit = Integer.MAX_VALUE;

		do
		{
			retry = false;

			CQLBatch lastBatch = null;

			//Used to reduce batch size with each retry
			limit = m_events.size() / divisor;
			try
			{
				Iterator<DataPointEvent> events = m_events.iterator();

				//Send new batch if not all events went out
				while (events.hasNext())
				{
					lastBatch = m_cqlBatchFactory.create();

					/*CQLBatch batch = new CQLBatch(m_consistencyLevel, m_session, m_schema,
							m_batchStats, m_loadBalancingPolicy);*/

					loadBatch(limit, lastBatch, events);

					lastBatch.submitBatch();

				}

			}
			//If More exceptions are added to retry they need to be added to IngestExecutorService
			catch (NoHostAvailableException nae)
			{
				clearCacheOfFailedBatch(lastBatch);
				//Throw this out so the back off retry can happen
				logger.error(nae.getMessage());
				throw nae;
			}
			catch (UnavailableException ue)
			{
				clearCacheOfFailedBatch(lastBatch);
				//Throw this out so the back off retry can happen
				logger.error(ue.getMessage());
				throw ue;
			}
			catch (Exception e)
			{
				clearCacheOfFailedBatch(lastBatch);
				if ("Batch too large".equals(e.getMessage()))
					logger.warn("Batch size is too large");
				else
					logger.error("Error sending data points", e);

				if (limit > 10)
				{
					retry = true;
					logger.info("Retrying batch with smaller limit");
				}
				else
				{
					logger.error("Failed to send data points", e);
					if (failedLogger.isTraceEnabled())
					{
						for (DataPointEvent event : m_events)
						{
							StringWriter sw = new StringWriter();
							JSONWriter jsonWriter = new JSONWriter(sw);
							jsonWriter.object();
							jsonWriter.key("name").value(event.getMetricName());
							jsonWriter.key("timestamp").value(event.getDataPoint().getTimestamp());
							jsonWriter.key("value");
							event.getDataPoint().writeValueToJson(jsonWriter);

							jsonWriter.key("tags").object();
							ImmutableSortedMap<String, String> tags = event.getTags();
							for (Map.Entry<String, String> entry : tags.entrySet())
							{
								jsonWriter.key(entry.getKey()).value(entry.getValue());
							}
							jsonWriter.endObject();

							jsonWriter.key("ttl").value(event.getTtl());

							jsonWriter.endObject();

							failedLogger.trace(sw.toString());
						}
					}
				}
			}
			divisor++;

		} while (retry);

		if (limit < m_events.size())
		{
			m_batchReductionPublisher.post(new BatchReductionEvent(limit));
		}

		m_callBack.complete();
	}
}
