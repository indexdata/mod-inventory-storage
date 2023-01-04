package org.folio.rest.support.kafka;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.folio.kafka.services.KafkaEnvironmentProperties;
import org.folio.rest.support.messages.EventMessage;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.serialization.JsonObjectDeserializer;

public class VertxMessageCollectingTopicConsumer {
  private final String topicName;
  private final GroupedMessageCollector messageCollector;
  private KafkaConsumer<String, JsonObject> consumer;

  public VertxMessageCollectingTopicConsumer(String topicName,
    GroupedMessageCollector messageCollector) {

    this.topicName = topicName;
    this.messageCollector = messageCollector;
  }

  void subscribe(Vertx vertx) {
    consumer = KafkaConsumer.create(vertx, consumerProperties());

    consumer.handler(messageCollector::acceptMessage);
    consumer.subscribe(topicName);
  }

  void unsubscribe() {
    if (consumer != null) {
      consumer.unsubscribe();
    }
  }

  Collection<EventMessage> receivedMessagesByKey(String key) {
    return messageCollector.messagesByGroupKey(key);
  }

  int countOfReceivedKeys() {
    return messageCollector.groupCount();
  }

  void discardCollectedMessages() {
    messageCollector.empty();
  }

  private static Map<String, String> consumerProperties() {
    Map<String, String> config = new HashMap<>();
    config.put("bootstrap.servers",
      KafkaEnvironmentProperties.host() + ":" + KafkaEnvironmentProperties.port());
    config.put("key.deserializer", StringDeserializer.class.getName());
    config.put("value.deserializer", JsonObjectDeserializer.class.getName());
    config.put("group.id", "folio_test");
    config.put("auto.offset.reset", "earliest");
    config.put("enable.auto.commit", "false");

    return config;
  }
}
