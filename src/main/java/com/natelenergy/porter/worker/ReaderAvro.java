package com.natelenergy.porter.worker;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.file.SeekableFileInput;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.avro.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Functions;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import com.natelenergy.porter.model.ValueProcessor;

public class ReaderAvro extends ProcessingReader {
  protected static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static class Converters {
    public static Function<Object, Object> UINT64 = new Function<Object, Object>() {
      @Override
      public Object apply(Object t) {
        GenericData.Fixed fixed = (GenericData.Fixed)t;
        long v = ByteBuffer.wrap(fixed.bytes()).order(ByteOrder.LITTLE_ENDIAN).getLong();
        return UnsignedLong.fromLongBits(v);
      }
    };
    public static Function<Object, Object> UINT32 = new Function<Object, Object>() {
      @Override
      public Object apply(Object t) {
        GenericData.Fixed fixed = (GenericData.Fixed)t;
        int v = ByteBuffer.wrap(fixed.bytes()).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return UnsignedInteger.fromIntBits(v);
      }
    };
    
    private static Map<String, Function<Object, Object>> map = new ConcurrentHashMap<>();
    static {
      map.put("UINT64", UINT64);
      map.put("UINT32", UINT32);
    }
  }
  
  DatumReader<GenericRecord> datumReader = new GenericDatumReader<>();
  long lastSync = 0;
  
  public ReaderAvro(Path file) {
    super(file);
  }
  
  public static Function<Object, Object> getNormalizer(Schema s) {
    Schema.Type t = s.getType();
    if(t == Type.DOUBLE || t == Type.FLOAT || t == Type.INT ||  t == Type.LONG || t == Type.BOOLEAN ) {
      return Functions.identity();
    }
    else if(t == Type.STRING) {
      return new Function<Object, Object>() {
        @Override
        public Object apply(Object t) {
          if( t instanceof Utf8 ) {
            return ((Utf8)t).toString();
          }
          return t;
        }
      };
    }
    else if(t == Type.ARRAY) {
      final Function<Object, Object> sub = getNormalizer(s.getElementType());
      return new Function<Object, Object>() {
        @Override
        public Object apply(Object t) {
          GenericData.Array arr = (GenericData.Array)t;
          List<Object> vals = new ArrayList<>(arr.size());
          for(int i=0; i<arr.size(); i++) {
            vals.add(sub.apply(arr.get(i)));
          }
          return vals;
        }
      };
    }
    else if(t == Type.ENUM) {
      final List<String> vals = s.getEnumSymbols();
      return new Function<Object, Object>() {
        @Override
        public Object apply(Object t) {
          return vals.get((int)t);
        }
      };
    }
    return Converters.map.get(s.getName());
  }
  
  public static class FConvert {
    public String name;
    public int index;
    public Function<Object, Object> norm;
    
    public FConvert(Field f) {
      this.name = f.name();
      this.index = f.pos();
      this.norm = getNormalizer(f.schema());

      if(this.norm == null) {
        LOGGER.info("TODO, convert: "+f.schema() + " // " + f);
      }
    }
  }
  

  @Override
  public long process(FileWorkerStatus status, ValueProcessor processor) throws Exception {
    if(!Files.exists(this.file)) {
      return 0;
    }
    if(processor == null) {
      throw new Exception("Missing processor!");
    }
   
    long count = 0;
    BasicFileAttributes attrs = Files.readAttributes(this.file, BasicFileAttributes.class);
    if(attrs.isRegularFile() && attrs.size() > lastSync) { // Must have a file size
      long total = 0;
      if(status.count!=null) {
        total = status.count;
      }
      
      GenericRecord record = null;
      try( 
        SeekableFileInput sin = new SeekableFileInput(this.file.toFile());
        DataFileReader<GenericRecord> dataFileReader = new DataFileReader<>(sin, datumReader) 
      ){
        // Schema schema = dataFileReader.getSchema();
        if(lastSync>0) {
          dataFileReader.seek(lastSync);
          dataFileReader.previousSync();
        }
        
        FConvert epoch = null;
        Schema schema = null;
        List<FConvert> fields = new ArrayList<>();
        Map<String, Object> values = new HashMap<>();
        
        while (dataFileReader.hasNext()) {
          record = dataFileReader.next(record);

          if(schema != record.getSchema()) {
            fields.clear();
            schema = record.getSchema();
            for(Field f : schema.getFields()) {
              FConvert c = new FConvert(f);
              if(c.norm!=null) {
                if("epoch".equals(c.name)) {
                  epoch = c;
                }
                else {
                  fields.add(c);
                }
              }
              else {
                LOGGER.warn("Skipping field: "+f );
              }
            }
          }

          values.clear();
          for(FConvert s : fields) {
            values.put(s.name, s.norm.apply( record.get(s.index)) );
          }

          long when = (long)record.get(epoch.index);
          processor.write(when, values);
          
          count++;
          status.count = total++;
          status.time = when;
          if(total % 10000 == 0) {
            LOGGER.info("indexing: "+this.file + " // Count:"+status.count + " // time:"+when );
          }
        }
        lastSync = dataFileReader.previousSync();
      }
      finally {
        processor.flush();
      }
    }
    LOGGER.trace("indexed:> "+this.file + " // Count:"+status.count + " // time:"+status.time );
    return count;
  }
}
