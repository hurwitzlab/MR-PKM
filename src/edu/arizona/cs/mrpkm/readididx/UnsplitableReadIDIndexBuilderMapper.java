package edu.arizona.cs.mrpkm.readididx;

import edu.arizona.cs.hadoop.fs.irods.output.HirodsMultipleOutputs;
import edu.arizona.cs.mrpkm.fastareader.types.FastaRead;
import edu.arizona.cs.mrpkm.utils.MultipleOutputsHelper;
import java.io.IOException;
import java.util.Hashtable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

/**
 *
 * @author iychoi
 */
public class UnsplitableReadIDIndexBuilderMapper extends Mapper<LongWritable, FastaRead, LongWritable, IntWritable> {
    
    private static final Log LOG = LogFactory.getLog(UnsplitableReadIDIndexBuilderMapper.class);
    
    private MultipleOutputs mos = null;
    private HirodsMultipleOutputs hmos = null;
    private Hashtable<String, Integer> namedOutputIDCache;
    private Hashtable<Integer, String> namedOutputCache;
    private int[] readIDs;
    
    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        if(MultipleOutputsHelper.isMultipleOutputs(context.getConfiguration())) {
            this.mos = new MultipleOutputs(context);
        }
        
        if(MultipleOutputsHelper.isHirodsMultipleOutputs(context.getConfiguration())) {
            this.hmos = new HirodsMultipleOutputs(context);
        }
        
        this.namedOutputIDCache = new Hashtable<String, Integer>();
        this.namedOutputCache = new Hashtable<Integer, String>();
        int numberOfOutputs = context.getConfiguration().getInt(ReadIDIndexHelper.getConfigurationKeyOfNamedOutputNum(), -1);
        if(numberOfOutputs <= 0) {
            throw new IOException("number of outputs is zero or negative");
        }
        
        this.readIDs = new int[numberOfOutputs];
        for(int i=0;i<this.readIDs.length;i++) {
            this.readIDs[i] = 0;
        }
    }
    
    @Override
    protected void map(LongWritable key, FastaRead value, Context context) throws IOException, InterruptedException {
        Integer namedoutputID = this.namedOutputIDCache.get(value.getFileName());
        if (namedoutputID == null) {
            namedoutputID = context.getConfiguration().getInt(ReadIDIndexHelper.getConfigurationKeyOfNamedOutputID(value.getFileName()), -1);
            if (namedoutputID < 0) {
                throw new IOException("No named output found : " + ReadIDIndexHelper.getConfigurationKeyOfNamedOutputID(value.getFileName()));
            }
            this.namedOutputIDCache.put(value.getFileName(), namedoutputID);
        }
        
        String namedOutput = this.namedOutputCache.get(namedoutputID);
        if (namedOutput == null) {
            String[] namedOutputs = context.getConfiguration().getStrings(ReadIDIndexHelper.getConfigurationKeyOfNamedOutputName(namedoutputID));
            if (namedOutputs.length != 1) {
                throw new IOException("no named output found");
            }
            namedOutput = namedOutputs[0];
            this.namedOutputCache.put(namedoutputID, namedOutput);
        }
        
        this.readIDs[namedoutputID]++;
        
        if (this.mos != null) {
            this.mos.write(namedOutput, new LongWritable(value.getReadOffset()), new IntWritable(this.readIDs[namedoutputID]));
        }

        if (this.hmos != null) {
            this.hmos.write(namedOutput, new LongWritable(value.getReadOffset()), new IntWritable(this.readIDs[namedoutputID]));
        }
    }
    
    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if(this.mos != null) {
            this.mos.close();
        }
        
        if(this.hmos != null) {
            this.hmos.close();
        }
        
        this.namedOutputIDCache = null;
        this.namedOutputCache = null;
    }
}