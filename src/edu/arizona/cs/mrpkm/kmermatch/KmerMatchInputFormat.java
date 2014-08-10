package edu.arizona.cs.mrpkm.kmermatch;

import edu.arizona.cs.mrpkm.types.CompressedSequenceWritable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;

/**
 *
 * @author iychoi
 */
public class KmerMatchInputFormat extends SequenceFileInputFormat<CompressedSequenceWritable, KmerMatchResult> {

    private static final Log LOG = LogFactory.getLog(KmerMatchInputFormat.class);

    @Override
    public RecordReader<CompressedSequenceWritable, KmerMatchResult> createRecordReader(InputSplit split, TaskAttemptContext context) throws IOException {
        return new KmerMatchRecordReader();
    }
    
    public static void setKmerSize(JobContext job, int kmerSize) {
        job.getConfiguration().setInt(KmerMatchHelper.getConfigurationKeyOfKmerSize(), kmerSize);
    }
    
    public static void setSliceNum(JobContext job, int numSlices) {
        job.getConfiguration().setInt(KmerMatchHelper.getConfigurationKeyOfSliceNum(), numSlices);
    }

    public List<InputSplit> getSplits(JobContext job) throws IOException {
        int kmerSize = job.getConfiguration().getInt(KmerMatchHelper.getConfigurationKeyOfKmerSize(), -1);
        if(kmerSize <= 0) {
            throw new IOException("kmer size must be a positive number");
        }
        
        int numSlices = job.getConfiguration().getInt(KmerMatchHelper.getConfigurationKeyOfSliceNum(), -1);
        if(numSlices <= 0) {
            throw new IOException("number of slices must be a positive number");
        }
        
        // generate splits
        List<InputSplit> splits = new ArrayList<InputSplit>();
        List<FileStatus> files = listStatus(job);
        List<Path> indexFiles = new ArrayList<Path>();
        for (FileStatus file : files) {
            Path path = file.getPath();
            long length = file.getLen();
            if(length > 0) {
                indexFiles.add(path);
            }
        }
        
        Path[] indexFilePaths = indexFiles.toArray(new Path[0]);
        
        for (int i=0;i<numSlices;i++) {
            KmerSequenceSlice slice = new KmerSequenceSlice(kmerSize, numSlices, i);
            splits.add(new KmerIndexSplit(indexFilePaths, slice));
        }
        
        // Save the number of input files in the job-conf
        job.getConfiguration().setLong(KmerMatchHelper.getConfigurationKeyOfInputFileNum(), files.size());

        LOG.debug("Total # of splits: " + splits.size());
        return splits;
    }
}
