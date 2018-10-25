package com.natelenergy.porter.worker;

import java.nio.file.Path;
import java.util.function.Supplier;

import com.natelenergy.porter.processor.ValueProcessor;

public abstract class ProcessingReader {
  
  protected final Path file;
  protected final Supplier<ValueProcessor> supplier;
  
  public ProcessingReader(Path file, Supplier<ValueProcessor> processor) {
    this.file = file;
    this.supplier = processor;
  }
  
  // This *can* get called multple times, so keep track of state outside
  public abstract long process(FileWorkerStatus status) throws Exception;
}
