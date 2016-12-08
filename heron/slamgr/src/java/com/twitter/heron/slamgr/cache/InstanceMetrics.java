//  Copyright 2016 Twitter. All rights reserved.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License
package com.twitter.heron.slamgr.cache;


import java.util.HashMap;
import java.util.Map;

import com.twitter.heron.proto.tmaster.TopologyMaster;
import com.twitter.heron.slamgr.SLAMetrics;

// Most granualar metrics/exception store level. This store exception and metrics
// associated with an instance.
public class InstanceMetrics {
  private String instance_id_;
  private int nbuckets_;
  private int bucket_interval_;
  // map between metric name and its values
  private Map<String, Metric> metrics_;

  // ctor. '_instance_id' is the id generated by heron. '_nbuckets' number of metrics buckets
  // stored for instances belonging to this component.
  public InstanceMetrics(String instance_id, int nbuckets, int bucket_interval) {
    instance_id_ = instance_id;
    nbuckets_ = nbuckets;
    bucket_interval_ = bucket_interval;

    metrics_ = new HashMap<>();
  }

  // Clear old metrics associated with this instance.
  public void Purge() {
    for (Metric m : metrics_.values()) {
      m.Purge();
    }
  }

  // Add metrics with name '_name' of type '_type' and value _value.
  public void AddMetricWithName(String name, SLAMetrics.MetricAggregationType type,
                                String value) {
    Metric metric_data = GetOrCreateMetric(name, type);
    metric_data.AddValueToMetric(value);
  }

  // Returns the metric metrics. Doesn't own _response.
  public void GetMetrics(TopologyMaster.MetricRequest request, long start_time, long end_time,
                         TopologyMaster.MetricResponse.Builder builder) {
    TopologyMaster.MetricResponse.TaskMetric.Builder tm = TopologyMaster.MetricResponse.TaskMetric.newBuilder();

    tm.setInstanceId(instance_id_);
    for (int i = 0; i < request.getMetricCount(); ++i) {
      String id = request.getMetric(i);
      if (metrics_.containsKey(id)) {
        TopologyMaster.MetricResponse.IndividualMetric im =
            metrics_.get(id).GetMetrics(request.getMinutely(), start_time, end_time);
        tm.addMetric(im);
      }
    }

    builder.addMetric(tm);
  }

  // Create or return existing Metric. Retains ownership of Metric object returned.
  private Metric GetOrCreateMetric(String name, SLAMetrics.MetricAggregationType type) {
    if (!metrics_.containsKey(name)) {
      metrics_.put(name, new Metric(name, type, nbuckets_, bucket_interval_));
    }
    return metrics_.get(name);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("instance: " + instance_id_).append(", nbuckets_: " + nbuckets_)
        .append(", interval: " + bucket_interval_).append(", data:\n");
    for (String k : metrics_.keySet()) {
      sb.append("{" + k + " ::> \n").append(metrics_.get(k)).append("}");
    }
    return sb.toString();
  }
}