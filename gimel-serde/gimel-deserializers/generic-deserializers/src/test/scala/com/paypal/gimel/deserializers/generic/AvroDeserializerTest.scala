/*
 * Copyright 2018 PayPal Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.paypal.gimel.deserializers.generic

import scala.collection.immutable.Map

import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.spark.sql.DataFrame
import org.apache.trevni.avro.RandomData
import org.scalatest._

import com.paypal.gimel.deserializers.generic.conf.{GenericDeserializerConfigs, GenericDeserializerConstants}
import com.paypal.gimel.logger.Logger
import com.paypal.gimel.serde.common.avro.AvroUtils
import com.paypal.gimel.serde.common.kafka.EmbeddedSingleNodeKafkaCluster
import com.paypal.gimel.serde.common.spark.SharedSparkSession

class AvroDeserializerTest extends FunSpec with Matchers with SharedSparkSession {
  val empAvroSchema =
    s"""{"namespace": "namespace",
          "type": "record",
          "name": "test_emp",
          "fields": [
              {\"name\": \"address\", \"type\": \"string\"},
              {\"name\": \"age\", \"type\": \"string\"},
              {\"name\": \"company\", \"type\": \"string\"},
              {\"name\": \"designation\", \"type\": \"string\"},
              {\"name\": \"id\", \"type\": \"string\"},
              {\"name\": \"name\", \"type\": \"string\"},
              {\"name\": \"salary\", \"type\": \"string\"}
         ]}""".stripMargin

  val avroDeserializer = new AvroDeserializer

  val kafkaCluster = new EmbeddedSingleNodeKafkaCluster()
  val logger = Logger()
  logger.setLogLevel("INFO")
  logger.consolePrintEnabled = true

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    kafkaCluster.start()
    kafkaCluster.schemaRegistry.restClient.registerSchema(empAvroSchema, "test_emp")
  }

  protected override def afterAll(): Unit = {
    super.afterAll()
    kafkaCluster.stop()
  }

  describe("deserialize with avro schema passed inline") {
    it("it should return a deserialized dataframe") {
      val props = Map(GenericDeserializerConfigs.avroSchemaStringKey -> empAvroSchema)
      val dataFrame = mockDataInDataFrame(10)
      val serializedDataframe = AvroUtils.dataFrametoBytes(dataFrame, empAvroSchema)
      val deserializedDF = avroDeserializer.deserialize(serializedDataframe, props)
      assert(deserializedDF.columns.sorted.sameElements(dataFrame.columns.sorted))
      assert(deserializedDF.except(dataFrame).count() == 0)
    }

    it("it should throw error if " + GenericDeserializerConfigs.avroSchemaStringKey + " is empty or not found") {
      val dataFrame = mockDataInDataFrame(10)
      val serializedDataframe = AvroUtils.dataFrametoBytes(dataFrame, empAvroSchema)
      val exception = intercept[IllegalArgumentException] {
        avroDeserializer.deserialize(serializedDataframe)
      }
      exception.getMessage.contains(s"You need to provide avro schema string with schema source ${GenericDeserializerConstants.avroSchemaInline}")
    }
  }

  describe("deserialize with avro schema stored in schema registry") {
    it ("it should return a deserialized dataframe") {
      val props = Map(GenericDeserializerConfigs.avroSchemaSourceUrlKey -> kafkaCluster.schemaRegistryUrl(),
        GenericDeserializerConfigs.avroSchemaSourceKey -> GenericDeserializerConstants.avroSchemaCSR,
        GenericDeserializerConfigs.avroSchemaSubjectKey -> "test_emp")
      logger.info(props)
      val dataFrame = mockDataInDataFrame(10)
      val serializedDataframe = AvroUtils.dataFrametoBytes(dataFrame, empAvroSchema)
      val deserializedDF: DataFrame = avroDeserializer.deserialize(serializedDataframe, props)
      assert(deserializedDF.columns.sorted.sameElements(dataFrame.columns.sorted))
      assert(deserializedDF.except(dataFrame).count() == 0)
    }

    it("it should throw error if " + GenericDeserializerConfigs.avroSchemaSubjectKey + " is empty or not found") {
      val props = Map(GenericDeserializerConfigs.avroSchemaSourceUrlKey -> kafkaCluster.schemaRegistryUrl(),
        GenericDeserializerConfigs.avroSchemaSourceKey -> GenericDeserializerConstants.avroSchemaCSR)
      val dataFrame = mockDataInDataFrame(10)
      val serializedDataframe = AvroUtils.dataFrametoBytes(dataFrame, empAvroSchema)
      val exception = intercept[IllegalArgumentException] {
        avroDeserializer.deserialize(serializedDataframe)
      }
      exception.getMessage.contains(s"You need to provide schema subject with schema source ${GenericDeserializerConstants.avroSchemaCSR}")
    }
  }

  describe("deserialize with avro schema source unknown") {
    it("it should throw error if " + GenericDeserializerConfigs.avroSchemaSourceKey + " is empty or unknown") {
      val props = Map(GenericDeserializerConfigs.avroSchemaSourceUrlKey -> kafkaCluster.schemaRegistryUrl(),
        GenericDeserializerConfigs.avroSchemaSourceKey -> "TEST")
      val dataFrame = mockDataInDataFrame(10)
      val serializedDataframe = AvroUtils.dataFrametoBytes(dataFrame, empAvroSchema)
      val exception = intercept[IllegalArgumentException] {
        avroDeserializer.deserialize(serializedDataframe)
      }
      exception.getMessage.contains(s"Unknown value of Schema Source")
    }
  }

  describe("deserialize with columnToDeserialize not present in input dataframe") {
    it("it should throw error if " + GenericDeserializerConfigs.columnToDeserializeKey + " is not present in input dataframe") {
      val props = Map(GenericDeserializerConfigs.avroSchemaSourceUrlKey -> kafkaCluster.schemaRegistryUrl(),
        GenericDeserializerConfigs.avroSchemaSourceKey -> "TEST",
        GenericDeserializerConfigs.columnToDeserializeKey -> "val")
      val dataFrame = mockDataInDataFrame(10)
      val serializedDataframe = AvroUtils.dataFrametoBytes(dataFrame, empAvroSchema)
      val exception = intercept[IllegalArgumentException] {
        avroDeserializer.deserialize(serializedDataframe)
      }
      exception.getMessage.contains(s"Column to Deserialize does not exist in dataframe")
    }
  }

  /*
  * Creates random avro GenericRecord for testing
  */
  def createRandomGenericRecord(avroSchema: String): GenericRecord = {
    val schema: Schema = (new Schema.Parser).parse(avroSchema)
    val it = new RandomData(schema, 1).iterator()
    val genericRecord = it.next().asInstanceOf[GenericData.Record]
    genericRecord
  }

  /*
   * Creates random avro record bytes for testing
   */
  def createRandomAvroRecordBytes(avroSchema: String): (Array[Byte], GenericRecord) = {
    val genericRecord = createRandomGenericRecord(avroSchema)
    val bytes = AvroUtils.genericRecordToBytes(genericRecord, avroSchema)
    (bytes, genericRecord)
  }

  // Mocks data for testing
  def mockAvroDataInDataFrame(numberOfRows: Int): DataFrame = {
    val dataFrame = mockDataInDataFrame(10)
    AvroUtils.dataFrametoBytes(dataFrame, empAvroSchema)
  }
}
