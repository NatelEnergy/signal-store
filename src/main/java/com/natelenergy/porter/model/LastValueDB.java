package com.natelenergy.porter.model;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.json.JSONStringer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.natelenergy.porter.util.JSONHelper;

import joptsimple.internal.Strings;

@JsonInclude(Include.NON_NULL)
@JsonTypeInfo(use=Id.CLASS, include=As.PROPERTY, property="@type")
public class LastValueDB extends StringBacked implements ValueProcessor {
  
  public String getJSONID() {
    return this.getClass().getSimpleName();
  }
  
  public static class LastValueSerializer extends JsonSerializer<LastValue> {
    @Override
    public void serialize(LastValue value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
      gen.writeStartArray();
      gen.writeObject(value.value);
      gen.writeNumber(value.time);
      gen.writeEndArray();
    }
  }

  public static class LastValueDeserializer extends JsonDeserializer<LastValue> {
    @Override
    public LastValue deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
      JsonToken t = p.getCurrentToken();
      if(t.id() != JsonToken.START_ARRAY.id() ) {
        throw new IOException("Expeced Start Array");
      }

      t = p.nextValue();
      LastValue v = new LastValue();
      v.value = p.readValueAs(Object.class);
      
      t = p.nextValue();
      v.time = p.getValueAsLong();

      t = p.nextToken();
      if(t.id() != JsonToken.END_ARRAY.id() ) {
        throw new IOException("Expeced End Array");
      }
      return v;
    }
  }

  @JsonDeserialize(using=LastValueDeserializer.class)
  @JsonSerialize(using=LastValueSerializer.class)
  public static class LastValue {
    long time;
    Object value;
    
    public long getTime() {
      return time;
    }

    public Object getValue() {
      return value;
    }
    
    public void write(JSONStringer jj) {
      jj.array();
      jj.value(value);
      jj.value(time);
      jj.endArray();
    }
  }
  
  private final Map<String, LastValue> db = new ConcurrentHashMap<>();

  public LastValueDB(String name, StringStore store, StringBackedConfigSupplier cfg) {
    super(name, store, cfg );
  }
  
  public void load(Map<String, LastValue> vals) {
    db.clear();
    db.putAll(vals);
  }

  public void load(ObjectMapper mapper, Reader reader) throws Exception {
    TypeReference<HashMap<String,LastValue>> typeRef 
      = new TypeReference<HashMap<String,LastValue>>() {};
    Map<String, LastValue> vals = mapper.readValue(reader, typeRef );
    db.clear();
    db.putAll(vals);
  }
  
  public int getCount() {
    return db.size();
  }
  
  @Override
  public void write(long time, String key, Object value) {
    LastValue old = db.get(key);
    if(old == null) {
      LastValue v = new LastValue();
      v.time = time;
      v.value = value;
      db.put(key, v);
      super.updated();
    }
    else if(time > old.time) {
      old.value = value;
      old.time = time;
      super.updated();
    }
  }

  @Override
  public void write(long time, Map<String, Object> record) {
    for(String k : record.keySet()) {
      this.write(time, k, record.get(k));
    }
  }

  @Override
  public void flush() {
    // nothing
  }
  
  public Map<String,LastValue> getDB(Predicate<String> name) {
    if(name != null) {
      Map<String, LastValue> vals = new HashMap<>();
      for(Map.Entry<String, LastValue> all : db.entrySet()) {
        if(name.test(all.getKey())) {
          vals.put(all.getKey(), all.getValue());
        }
      }
      return vals;
    }
    return java.util.Collections.unmodifiableMap(db);
  }
  
  public LastValue get(String field) {
    return db.get(field);
  }

  @Override
  protected void load(String str) throws Exception {
    if(Strings.isNullOrEmpty(str)) {
      db.clear();
    }
    else {
      this.load(JSONHelper.mapper, new StringReader(str));
    }
  }

  @Override
  protected String getSaveString() throws Exception {
    return JSONHelper.mapper.writeValueAsString(db);
  }

  public int size() {
    return db.size();
  }
  
  @Override
  public void close() throws IOException {
    
  }
}
