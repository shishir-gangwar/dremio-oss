/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.catalog.conf;

import java.lang.reflect.Field;
import java.util.Arrays;

import javax.inject.Provider;

import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.service.namespace.AbstractConnectionConf;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;

import io.protostuff.ByteString;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtobufIOUtil;
import io.protostuff.Schema;
import io.protostuff.runtime.RuntimeSchema;

/**
 * Abstract class describing a Source Configuration.
 *
 * Note, we disable getters/setters for Jackson because we should be interacting directly with
 * fields (same as proto encoding). We also avoid using @JsonIgnore annotation as it causes problems
 * when used in tandem with field serialization.
 *
 * We also are claiming that we use JsonTypeName resolution but that isn't actually true. We are
 * using pre-registration using the registerSubTypes() method below to ensure that everything is
 * named correctly. The SourceType(value=<name>) annotation parameter is what is used for
 * serialization/deserialization in JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "type")
@JsonAutoDetect(fieldVisibility=Visibility.PUBLIC_ONLY, getterVisibility=Visibility.NONE, isGetterVisibility=Visibility.NONE, setterVisibility=Visibility.NONE)
public abstract class ConnectionConf<T extends ConnectionConf<T, P>, P extends StoragePlugin> implements AbstractConnectionConf {

  private transient final Schema<T> schema;

  @SuppressWarnings("unchecked")
  protected ConnectionConf() {
    this.schema = (Schema<T>) RuntimeSchema.getSchema(getClass());
  }

  public void clearSecrets() {
    clear(new Predicate<Field>() {
      @Override
      public boolean apply(Field field) {
        return field.isAnnotationPresent(Secret.class);
      }});
  }

  public void clearNotMetadataImpacting() {
    clear(new Predicate<Field>() {
      @Override
      public boolean apply(Field field) {
        return field.isAnnotationPresent(NotMetadataImpacting.class);
      }});
  }

  /**
   * Clears all fields that match a particular predicate. For all primitive types, the value is set
   * to zero. For object types, the value is set to null.
   *
   * @param predicate
   */
  private void clear(Predicate<Field> predicate) {
    try {
      for(Field field : this.getClass().getDeclaredFields()) {
        if(predicate.apply(field)) {
          if(field.getType().equals(int.class)) {
            field.setInt(this, 0);
          } else if (field.getType().equals(double.class)) {
            field.setDouble(this, 0);
          } else if (field.getType().equals(long.class)) {
            field.setLong(this, 0);
          } else if (field.getType().equals(float.class)) {
            field.setFloat(this, 0);
          } else if (field.getType().equals(short.class)) {
            field.setShort(this, (short) 0);
          } else if (field.getType().equals(byte.class)) {
            field.setByte(this, (byte) 0);
          } else if (field.getType().equals(char.class)) {
            field.setChar(field.getType(), (char) 0);
          } else if (field.getType().equals(boolean.class)) {
            field.setBoolean(this, false);
          } else {
            field.set(this, null);
          }
        }
      }
    } catch (IllegalAccessException e) {
      throw Throwables.propagate(e);
    }
  }

  public static void registerSubTypes(ObjectMapper mapper, ScanResult scanResult) {
    for(Class<?> c : scanResult.getAnnotatedClasses(SourceType.class)) {
      NamedType nt = new NamedType(c, c.getAnnotation(SourceType.class).value());
      mapper.registerSubtypes(nt);
    }
  }

  @Override
  public boolean equals(Object other) {
    if(other == null || !(other instanceof ConnectionConf) ) {
      return false;
    }
    final ConnectionConf<?, ?> o = (ConnectionConf<?, ?>) other;

    if(!o.getType().equals(getType())) {
      return false;
    }
    return Arrays.equals(toBytes(),  o.toBytes());
  }

  @Override
  public ByteString toBytesString() {
    return ByteString.copyFrom(toBytes());
  }

  @SuppressWarnings("unchecked")
  public byte[] toBytes() {
    return ProtobufIOUtil.toByteArray( (T) this, schema, LinkedBuffer.allocate() );
  }

  @Override
  public final T clone() {
    byte[] bytes = toBytes();
    T message = schema.newMessage();
    ProtobufIOUtil.mergeFrom(bytes, message, schema);
    return message;
  }

  /**
   * Indicates whether the other conf is equal to this one, ignoring fields that do not impact metadata.
   *
   * @param other connection conf
   * @return true if this connection conf equals other conf, ignoring fields that do not impact metadata
   */
  public final boolean equalsIgnoringNotMetadataImpacting(ConnectionConf<?, ?> other) {
    ConnectionConf<?, ?> left = clone();
    ConnectionConf<?, ?> right = other.clone();

    left.clearNotMetadataImpacting();
    right.clearNotMetadataImpacting();
    return left.equals(right);
  }

  @Override
  public final String getType() {
    return this.getClass().getAnnotation(SourceType.class).value();
  }

  @Override
  public final int hashCode() {
    return Arrays.hashCode(toBytes());
  }

  public abstract P newPlugin(final SabotContext context, final String name, Provider<StoragePluginId> pluginIdProvider);

  public boolean isInternal() {
    return false;
  }
}
