package edu.arizona.cs.mrpkm.kmermatch;

import edu.arizona.cs.mrpkm.kmerrangepartitioner.KmerRangePartition;
import edu.arizona.cs.mrpkm.kmeridx.AKmerIndexReader;
import edu.arizona.cs.mrpkm.kmeridx.FilteredKmerIndexReader;
import edu.arizona.cs.mrpkm.kmeridx.KmerIndexHelper;
import edu.arizona.cs.mrpkm.stddeviation.KmerStdDeviationHelper;
import edu.arizona.cs.mrpkm.stddeviation.KmerStdDeviationReader;
import edu.arizona.cs.mrpkm.types.CompressedIntArrayWritable;
import edu.arizona.cs.mrpkm.types.CompressedSequenceWritable;
import edu.arizona.cs.mrpkm.utils.FileSystemHelper;
import edu.arizona.cs.mrpkm.utils.SequenceHelper;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 *
 * @author iychoi
 */
public class KmerLinearMatcher {
    
    private static final Log LOG = LogFactory.getLog(KmerLinearMatcher.class);
    
    private static final int REPORT_FREQUENCY = 1000000;
    
    private Path[] inputIndexPaths;
    private KmerRangePartition slice;
    private Configuration conf;
    
    private AKmerIndexReader[] readers;
    private BigInteger sliceSize;
    private BigInteger currentProgress;
    private BigInteger beginSequence;
    
    private KmerMatchResult curMatch;
    private CompressedSequenceWritable[] stepKeys;
    private CompressedIntArrayWritable[] stepVals;
    private List<Integer> stepMinKeys;
    private boolean stepStarted;
    private int reportCounter;
    
    public KmerLinearMatcher(Path[] inputIndexPaths, KmerRangePartition slice, String filterPath, Configuration conf) throws IOException {
        initialize(inputIndexPaths, slice, filterPath, conf);
    }
    
    private void initialize(Path[] inputIndexPaths, KmerRangePartition slice, String filterPath, Configuration conf) throws IOException {
        this.inputIndexPaths = inputIndexPaths;
        this.slice = slice;
        this.conf = conf;
        
        Path[][] indice = KmerIndexHelper.groupKmerIndice(this.inputIndexPaths);
        this.readers = new AKmerIndexReader[indice.length];
        LOG.info("# of KmerIndexReader : " + indice.length);
        for(int i=0;i<indice.length;i++) {
            FileSystem fs = indice[i][0].getFileSystem(this.conf);
            String fastaFilename = KmerIndexHelper.getFastaFileName(indice[i][0]);
            String stddevFilename = KmerStdDeviationHelper.makeStdDeviationFileName(fastaFilename);
            Path stdDeviationPath = new Path(filterPath, stddevFilename);
            
            KmerStdDeviationReader stddevReader = new KmerStdDeviationReader(stdDeviationPath, this.conf);
            double avg = stddevReader.getAverageCounts();
            double stddev = stddevReader.getStandardDeviation();
            double factor = 2;
            this.readers[i] = new FilteredKmerIndexReader(fs, FileSystemHelper.makeStringFromPath(indice[i]), this.slice.getPartitionBeginKmer(), this.slice.getPartitionEndKmer(), this.conf, avg, stddev, factor);
        }
        
        this.sliceSize = slice.getPartitionSize();
        this.currentProgress = BigInteger.ZERO;
        this.beginSequence = this.slice.getPartitionBegin();
        this.curMatch = null;
        this.stepKeys = new CompressedSequenceWritable[this.readers.length];
        this.stepVals = new CompressedIntArrayWritable[this.readers.length];
        this.stepStarted = false;
        this.reportCounter = 0;
        
        LOG.info("Matcher is initialized");
        LOG.info("> Range " + this.slice.getPartitionBeginKmer() + " ~ " + this.slice.getPartitionEndKmer());
        LOG.info("> Num of Slice Entries : " + this.slice.getPartitionSize().longValue());
    }
    
    /*
    public void reset() throws IOException {
        for(AKmerIndexReader reader : this.readers) {
            reader.reset();
        }
        
        this.currentProgress = BigInteger.ZERO;
        this.curMatch = null;
        this.stepKeys = new CompressedSequenceWritable[this.readers.length];
        this.stepVals = new CompressedIntArrayWritable[this.readers.length];
        this.stepStarted = false;
        this.reportCounter = 0;
    }
    */
    
    public boolean nextMatch() throws IOException {
        List<Integer> minKeyIndice = getNextMinKeys();
        while(minKeyIndice.size() > 0) {
            CompressedSequenceWritable minKey = this.stepKeys[minKeyIndice.get(0)];
            this.reportCounter++;
            if(this.reportCounter >= REPORT_FREQUENCY) {
                this.currentProgress = SequenceHelper.convertToBigInteger(minKey.getSequence()).subtract(this.beginSequence).add(BigInteger.ONE);
                this.reportCounter = 0;
                LOG.info("Reporting Progress : " + this.currentProgress);
            }

            // check matching
            if (minKeyIndice.size() > 1) {
                CompressedIntArrayWritable[] minVals = new CompressedIntArrayWritable[minKeyIndice.size()];
                String[][] minIndexPaths = new String[minKeyIndice.size()][];

                int valIdx = 0;
                for (int idx : minKeyIndice) {
                    minVals[valIdx] = this.stepVals[idx];
                    minIndexPaths[valIdx] = this.readers[idx].getIndexPaths();
                    valIdx++;
                }

                this.curMatch = new KmerMatchResult(minKey, minVals, minIndexPaths);
                return true;
            }
            
            minKeyIndice = getNextMinKeys();
        }
        
        // step failed and no match
        this.curMatch = null;
        this.currentProgress = this.sliceSize;
        return false;
    }
    
    private List<Integer> findMinKeys() throws IOException {
        CompressedSequenceWritable minKey = null;
        List<Integer> minKeyIndice = new ArrayList<Integer>();
        for(int i=0;i<this.readers.length;i++) {
            if(this.stepKeys[i] != null) {
                if(minKey == null) {
                    minKey = this.stepKeys[i];
                    minKeyIndice.clear();
                    minKeyIndice.add(i);
                } else {
                    int comp = minKey.compareTo(this.stepKeys[i]);
                    if (comp == 0) {
                        // found same min key
                        minKeyIndice.add(i);
                    } else if (comp > 0) {
                        // found smaller one
                        minKey = this.stepKeys[i];
                        minKeyIndice.clear();
                        minKeyIndice.add(i);
                    }
                }
            }
        }

        return minKeyIndice;
    }
    
    private List<Integer> getNextMinKeys() throws IOException {
        if(!this.stepStarted) {
            for(int i=0;i<this.readers.length;i++) {
                // fill first
                CompressedSequenceWritable key = new CompressedSequenceWritable();
                CompressedIntArrayWritable val = new CompressedIntArrayWritable();
                if(this.readers[i].next(key, val)) {
                    this.stepKeys[i] = key;
                    this.stepVals[i] = val;
                } else {
                    this.stepKeys[i] = null;
                    this.stepVals[i] = null;
                }
            }
            
            this.stepStarted = true;
            this.stepMinKeys = findMinKeys();
            return this.stepMinKeys;
        } else {
            // find min key
            if(this.stepMinKeys.size() == 0) {
                //EOF
                return this.stepMinKeys;
            }
            
            // move min pointers
            for (int idx : this.stepMinKeys) {
                CompressedSequenceWritable key = new CompressedSequenceWritable();
                CompressedIntArrayWritable val = new CompressedIntArrayWritable();
                if(this.readers[idx].next(key, val)) {
                    this.stepKeys[idx] = key;
                    this.stepVals[idx] = val;
                } else {
                    this.stepKeys[idx] = null;
                    this.stepVals[idx] = null;
                }
            }
            
            this.stepMinKeys = findMinKeys();
            return this.stepMinKeys;
        }
    }
    
    public KmerMatchResult getCurrentMatch() {
        return this.curMatch;
    }
    
    public float getProgress() {
        if (this.sliceSize.compareTo(this.currentProgress) <= 0) {
            return 1.0f;
        } else {
            BigInteger divided = this.currentProgress.multiply(BigInteger.valueOf(100)).divide(this.sliceSize);
            float f = divided.floatValue() / 100;
            
            return Math.min(1.0f, f);
        }
    }
    
    public void close() throws IOException {
        for(AKmerIndexReader reader : this.readers) {
            reader.close();
        }
    }
}
