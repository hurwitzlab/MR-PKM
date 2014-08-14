package edu.arizona.cs.mrpkm.kmeridx;

import edu.arizona.cs.mrpkm.types.CompressedIntArrayWritable;
import edu.arizona.cs.mrpkm.types.CompressedSequenceWritable;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.MapFile;

/**
 *
 * @author iychoi
 */
public class MultiKmerIndexReader extends AKmerIndexReader {
    
    private static final Log LOG = LogFactory.getLog(MultiKmerIndexReader.class);
        
    private FileSystem fs;
    private String[] indexPaths;
    private Configuration conf;
    private MapFile.Reader[] mapfileReaders;
    
    private CompressedSequenceWritable currentKey = null;
    private CompressedIntArrayWritable currentVal = null;
    
    private CompressedSequenceWritable[] keys;
    private CompressedIntArrayWritable[] vals;
    
    private int currentIndex;
    
    public MultiKmerIndexReader(FileSystem fs, String[] indexPaths, Configuration conf) throws IOException {
        initialize(fs, indexPaths, null, conf);
    }
    
    public MultiKmerIndexReader(FileSystem fs, String[] indexPaths, CompressedSequenceWritable beginKey, Configuration conf) throws IOException {
        initialize(fs, indexPaths, beginKey, conf);
    }
    
    public MultiKmerIndexReader(FileSystem fs, String[] indexPaths, String beginKey, Configuration conf) throws IOException {
        initialize(fs, indexPaths, new CompressedSequenceWritable(beginKey), conf);
    }
    
    private void initialize(FileSystem fs, String[] indexPaths, CompressedSequenceWritable beginKey, Configuration conf) throws IOException {
        this.fs = fs;
        this.indexPaths = indexPaths;
        this.conf = conf;

        this.mapfileReaders = new MapFile.Reader[indexPaths.length];
        for(int i=0;i<indexPaths.length;i++) {
            this.mapfileReaders[i] = new MapFile.Reader(fs, indexPaths[i], conf);
            if(beginKey != null) {
                mapfileReaders[i].seek(beginKey);
            }
        }
        
        this.keys = new CompressedSequenceWritable[indexPaths.length];
        this.vals = new CompressedIntArrayWritable[indexPaths.length];
        
        fillKV();
    }
    
    private void fillKV() throws IOException {
        for(int i=0;i<this.indexPaths.length;i++) {
            CompressedSequenceWritable key = new CompressedSequenceWritable();
            CompressedIntArrayWritable val = new CompressedIntArrayWritable();

            if(this.mapfileReaders[i].next(key, val)) {
                this.keys[i] = key;
                this.vals[i] = val;
            } else {
                this.keys[i] = null;
                this.vals[i] = null;
            }
        }

        CompressedSequenceWritable minKey = null;
        this.currentIndex = -1;
        for(int i=0;i<this.keys.length;i++) {
            if(this.keys[i] != null) {
                if(minKey == null) {
                    minKey = this.keys[i];
                    this.currentIndex = i;
                } else {
                    if(minKey.compareTo(this.keys[i]) > 0) {
                        minKey = this.keys[i];
                        this.currentIndex = i;
                    }
                }
            }
        }
        
        if(this.currentIndex < 0) {
            LOG.info("Could not found min key");
        }
    }
    
    @Override
    public void reset() throws IOException {
        for(int i=0;i<this.mapfileReaders.length;i++) {
            this.mapfileReaders[i].reset();
        }
        fillKV();
    }

    @Override
    public void close() throws IOException {
        if(this.mapfileReaders != null) {
            for(int i=0;i<this.mapfileReaders.length;i++) {
                this.mapfileReaders[i].close();
                this.mapfileReaders[i] = null;
            }
            this.mapfileReaders = null;
        }
    }
    
    @Override
    public String[] getIndexPaths() {
        return this.indexPaths;
    }
    
    @Override
    public void seek(String sequence) throws IOException {
        seek(new CompressedSequenceWritable(sequence));
    }
    
    @Override
    public void seek(CompressedSequenceWritable key) throws IOException {
        for(MapFile.Reader reader : this.mapfileReaders) {
            reader.seek(key);
        }
        
        fillKV();
    }
    
    @Override
    public boolean next(CompressedSequenceWritable key, CompressedIntArrayWritable val) throws IOException {
        if(this.currentIndex < 0) {
            this.currentKey = null;
            this.currentVal = null;
            return false;
        }
        
        this.currentKey = this.keys[this.currentIndex];
        this.currentVal = this.vals[this.currentIndex];
        
        CompressedSequenceWritable myKey = new CompressedSequenceWritable();
        CompressedIntArrayWritable myVal = new CompressedIntArrayWritable();
        
        if(this.mapfileReaders[this.currentIndex].next(myKey, myVal)) {
            this.keys[this.currentIndex] = myKey;
            this.vals[this.currentIndex] = myVal;
        } else {
            this.keys[this.currentIndex] = null;
            this.vals[this.currentIndex] = null;
        }

        CompressedSequenceWritable minKey = null;
        this.currentIndex = -1;
        for(int i=0;i<this.keys.length;i++) {
            if(this.keys[i] != null) {
                if(minKey == null) {
                    minKey = this.keys[i];
                    this.currentIndex = i;
                } else {
                    if(minKey.compareTo(this.keys[i]) > 0) {
                        minKey = this.keys[i];
                        this.currentIndex = i;
                    }
                }
            }
        }
        
        key.set(this.currentKey);
        val.set(this.currentVal);
        
        if(this.currentKey != null) {
            return true;
        } else {
            return false;
        }
    }
}