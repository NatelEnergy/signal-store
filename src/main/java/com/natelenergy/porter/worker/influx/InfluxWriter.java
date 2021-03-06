package com.natelenergy.porter.worker.influx;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.NotThreadSafe;

import org.influxdb.InfluxDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.natelenergy.porter.model.ValueProcessor;

@JsonInclude(Include.NON_NULL)
@JsonTypeInfo(use=Id.CLASS, include=As.PROPERTY, property="@type")
@NotThreadSafe
public class InfluxWriter implements ValueProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected static final int MAX_FRACTION_DIGITS = 340;
  protected static final ThreadLocal<NumberFormat> NUMBER_FORMATTER =
          ThreadLocal.withInitial(() -> {
            NumberFormat numberFormat = NumberFormat.getInstance(Locale.ENGLISH);
            numberFormat.setMaximumFractionDigits(MAX_FRACTION_DIGITS);
            numberFormat.setGroupingUsed(false);
            numberFormat.setMinimumFractionDigits(1);
            return numberFormat;
          });
  
  protected final String measurment;
  protected final InfluxDB influx;
  protected final StringBuilder sb;
  
  protected final int maxBuffer = 1024*1024*5; // 5MB 
  protected long count = 0;
  
  public InfluxWriter(InfluxDB influx, String measurment)
  {
    this.measurment = measurment;
    this.influx = influx;
    this.sb = new StringBuilder();
  }

  public String getJSONID() {
    return this.getClass().getSimpleName() + " :: " + measurment;
  }
  
  private void escapeField(final String field) {
    for (int i = 0; i < field.length(); i++) {
      switch (field.charAt(i)) {
        case '\\':
        case '\"':
          sb.append('\\');
        default:
          sb.append(field.charAt(i));
      }
    }
  }
  
  protected void append(Object value) {
    if (value instanceof Number) {
      if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
        sb.append(NUMBER_FORMATTER.get().format(value));
      } else {
        sb.append(value).append('i');
      }
    } else if (value instanceof String) {
      String stringValue = (String) value;
      sb.append('"');
      escapeField(stringValue);
      sb.append('"');
    } else {
      sb.append(value);
    }
  }

  @Override
  public void write(long time, String key, Object value) {
    if(value == null) {
      return;
    }
    
    sb.append(measurment).append(",f=").append(key);
    sb.append(' ');
    sb.append(key).append('=');
    append(value);
    sb.append(' ');
    sb.append(TimeUnit.MILLISECONDS.toNanos(time));
    sb.append('\n');

    count++;
    if(sb.length() > maxBuffer) {
      this.flush();
    }
  }

  @Override
  public void write(long time, Map<String, Object> record) {
    sb.append(measurment).append(' ');
    
    for (Entry<String, Object> field : record.entrySet()) {
      Object value = field.getValue();
      if (value == null) {
        continue;
      }
      sb.append( field.getKey() );
      sb.append('=');
      append(value);
      sb.append(',');
    }

    // efficiently chop off the trailing comma
    int lengthMinusOne = sb.length() - 1;
    if (sb.charAt(lengthMinusOne) == ',') {
      sb.setLength(lengthMinusOne);
    }
    
    sb.append(' ');
    sb.append(TimeUnit.MILLISECONDS.toNanos(time));
    sb.append('\n');
    
    count++;
    if(sb.length() > maxBuffer) {
      this.flush();
    }
  }

  @Override
  public void flush() {
    if(sb.length()>0) {
    //  LOGGER.info("Flush: "+this.count + " // " + sb.length());
      
      influx.write(sb.toString());
      sb.setLength(0);
    }
    this.count = 0;
  }
  
  @Override
  public void close() throws IOException {
    influx.close();
  }
}
