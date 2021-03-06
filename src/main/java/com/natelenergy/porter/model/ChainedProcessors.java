package com.natelenergy.porter.model;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

@JsonInclude(Include.NON_NULL)
@JsonTypeInfo(use=Id.CLASS, include=As.PROPERTY, property="@type")
public class ChainedProcessors implements ValueProcessor {
  
  public final ValueProcessor[] chain;
  
  public ChainedProcessors(ValueProcessor ... chain) {
    this.chain = chain;
  }
  public ChainedProcessors(List<ValueProcessor> chain) {
    this.chain = chain.toArray( new ValueProcessor[chain.size()] );
  }
  
  @Override
  public void write(long time, String key, Object value) {
    for(ValueProcessor p : chain) {
      p.write(time, key, value);
    }
  }

  @Override
  public void write(long time, Map<String, Object> record) {
    for(ValueProcessor p : chain) {
      p.write(time, record);
    }
  }

  @Override
  public void flush() {
    for(ValueProcessor p : chain) {
      p.flush();
    }
  }
  
  @Override
  public String toString() {
    StringBuilder str = new StringBuilder();
    str.append("Chain [");
    for(int i=0; i<chain.length; i++ ) {
      if(i>0) {
        str.append(',');
      }
      str.append(chain[i].toString());
    }
    return str.toString();
  }
  
  @Override
  public void close() throws IOException {
    for(ValueProcessor p : chain) {
      p.close();
    }
  }
}
