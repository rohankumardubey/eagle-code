/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.eagle.app.messaging;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import com.google.common.base.Preconditions;
import com.typesafe.config.Config;
import org.apache.eagle.app.environment.builder.MetricDefinition;
import org.apache.eagle.app.utils.StreamConvertHelper;
import org.apache.eagle.log.entity.GenericMetricEntity;
import org.apache.eagle.log.entity.GenericServiceAPIResponseEntity;
import org.apache.eagle.service.client.IEagleServiceClient;
import org.apache.eagle.service.client.impl.BatchSender;
import org.apache.eagle.service.client.impl.EagleServiceClientImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MetricStreamPersist extends BaseRichBolt  {
    private static final Logger LOG = LoggerFactory.getLogger(MetricStreamPersist.class);

    private final Config config;
    private final MetricMapper mapper;
    private final int batchSize;
    private IEagleServiceClient client;
    private OutputCollector collector;
    private BatchSender batchSender;

    public MetricStreamPersist(MetricDefinition metricDefinition, Config config) {
        this.config = config;
        this.mapper = new StructuredMetricMapper(metricDefinition);
        this.batchSize = config.hasPath("service.batchSize") ? config.getInt("service.batchSize") : 1;
    }

    public MetricStreamPersist(MetricMapper mapper, Config config) {
        this.config = config;
        this.mapper = mapper;
        this.batchSize = config.hasPath("service.batchSize") ? config.getInt("service.batchSize") : 1;
    }

    @Override
    public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
        this.client = new EagleServiceClientImpl(config);
        if (this.batchSize > 0) {
            this.batchSender = client.batch(this.batchSize);
        }
        this.collector = collector;
    }

    @Override
    public void execute(Tuple input) {
        try {
            GenericMetricEntity metricEntity = this.mapper.map(StreamConvertHelper.tupleToEvent(input).f1());
            if (batchSize <= 1) {
                GenericServiceAPIResponseEntity<String> response = this.client.create(Collections.singletonList(metricEntity));
                if (!response.isSuccess()) {
                    LOG.error("Service side error: {}", response.getException());
                    collector.reportError(new IllegalStateException(response.getException()));
                } else {
                    collector.ack(input);
                }
            } else {
                this.batchSender.send(metricEntity);
                collector.ack(input);
            }
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), ex);
            collector.reportError(ex);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("f1"));
    }

    @Override
    public void cleanup() {
        try {
            this.client.close();
        } catch (IOException e) {
            LOG.error("Close client error: {}", e.getMessage(), e);
        } finally {
            super.cleanup();
        }
    }

    @FunctionalInterface
    public interface MetricMapper extends Serializable {
        GenericMetricEntity map(Map event);
    }

    public class StructuredMetricMapper implements MetricMapper {
        private final MetricDefinition metricDefinition;

        private StructuredMetricMapper(MetricDefinition metricDefinition) {
            this.metricDefinition = metricDefinition;
        }

        @Override
        public GenericMetricEntity map(Map event) {
            String metricName = metricDefinition.getNameSelector().getMetricName(event);
            Preconditions.checkNotNull(metricName,"Metric name is null");
            Long timestamp = metricDefinition.getTimestampSelector().getTimestamp(event);
            Preconditions.checkNotNull(timestamp, "Timestamp is null");
            Map<String,String> tags = new HashMap<>();
            for (String dimensionField: metricDefinition.getDimensionFields()) {
                Preconditions.checkNotNull(dimensionField,"Dimension field name is null");
                tags.put(dimensionField, (String) event.get(dimensionField));
            }

            double[] values;
            if (event.containsKey(metricDefinition.getValueField())) {
                values = new double[] {(double) event.get(metricDefinition.getValueField())};
            } else {
                LOG.warn("Event has no value field '{}': {}, use 0 by default", metricDefinition.getValueField(), event);
                values = new double[]{0};
            }

            GenericMetricEntity entity = new GenericMetricEntity();
            entity.setPrefix(metricName);
            entity.setTimestamp(timestamp);
            entity.setTags(tags);
            entity.setValue(values);
            return entity;
        }
    }
}