package edu.arizona.cs.mrpkm.kmeridx;

import edu.arizona.cs.mrpkm.kmeridx.types.CompressedIntArrayWritable;
import edu.arizona.cs.mrpkm.kmeridx.types.CompressedSequenceWritable;
import edu.arizona.cs.mrpkm.kmeridx.types.MultiFileCompressedSequenceWritable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

/**
 *
 * @author iychoi
 */
public class KmerIndexBuilderReducer extends Reducer<MultiFileCompressedSequenceWritable, CompressedIntArrayWritable, CompressedSequenceWritable, CompressedIntArrayWritable> {
    
    private static final Log LOG = LogFactory.getLog(KmerIndexBuilderReducer.class);
    
    private MultipleOutputs mos;
    private Hashtable<Integer, String> namedOutputCache;
    
    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        this.mos = new MultipleOutputs(context);
        this.namedOutputCache = new Hashtable<Integer, String>();
    }
    
    @Override
    protected void reduce(MultiFileCompressedSequenceWritable key, Iterable<CompressedIntArrayWritable> values, Context context) throws IOException, InterruptedException {
        List<Integer> readIDs = new ArrayList<Integer>();
        
        for(CompressedIntArrayWritable value : values) {
            for(int ivalue : value.get()) {
                readIDs.add(ivalue);
            }
        }
        
        int namedoutputID = key.getFileID();
        String namedOutput = this.namedOutputCache.get(namedoutputID);
        if (namedOutput == null) {
            String[] namedOutputs = context.getConfiguration().getStrings(KmerIndexConstants.CONF_NAMED_OUTPUT_ID_PREFIX + namedoutputID);
            if (namedOutputs.length != 1) {
                throw new IOException("no named output found");
            }
            namedOutput = namedOutputs[0];
            this.namedOutputCache.put(namedoutputID, namedOutput);
        }
        
        CompressedSequenceWritable outKey = new CompressedSequenceWritable(key.getCompressedSequence(), key.getSequenceLength());
        
        this.mos.write(namedOutput, outKey, new CompressedIntArrayWritable(readIDs));
        //context.write(key, new Text(sb.toString()));
    }
    
    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        this.mos.close();
        this.namedOutputCache = null;
    }
}