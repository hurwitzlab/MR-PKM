package edu.arizona.cs.mrpkm.kmeridx;

import edu.arizona.cs.mrpkm.cluster.MRClusterConfiguration;
import edu.arizona.cs.mrpkm.types.CompressedIntArrayWritable;
import edu.arizona.cs.mrpkm.types.CompressedSequenceWritable;
import edu.arizona.cs.mrpkm.types.MultiFileCompressedSequenceWritable;
import edu.arizona.cs.mrpkm.fastareader.FastaReadInputFormat;
import edu.arizona.cs.mrpkm.types.NamedOutput;
import edu.arizona.cs.mrpkm.types.NamedOutputs;
import edu.arizona.cs.mrpkm.utils.FileSystemHelper;
import edu.arizona.cs.mrpkm.utils.MapReduceHelper;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MapFileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 *
 * @author iychoi
 */
public class KmerIndexBuilder extends Configured implements Tool {
    
    private static final Log LOG = LogFactory.getLog(KmerIndexBuilder.class);
    
    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new KmerIndexBuilder(), args);
        System.exit(res);
    }
    
    @Override
    public int run(String[] args) throws Exception {
        String clusterConfiguration = null;
        String inputPath = null;
        String readIDIndexPath = null;
        String outputPath = null;
        int kmerSize = 0;
        int nodeSize = 0;
        boolean useBloomMap = false;
        
        if(args.length == 6) {
            clusterConfiguration = "default";
            useBloomMap = Boolean.parseBoolean(args[0]);
            kmerSize = Integer.parseInt(args[1]);
            nodeSize = Integer.parseInt(args[2]);
            inputPath = args[3];
            readIDIndexPath = args[4];
            outputPath = args[5];
        } else if(args.length >= 7) {
            clusterConfiguration = args[0];
            useBloomMap = Boolean.parseBoolean(args[1]);
            kmerSize = Integer.parseInt(args[2]);
            nodeSize = Integer.parseInt(args[3]);
            inputPath = "";
            for(int i=4;i<args.length-2;i++) {
                if(!inputPath.equals("")) {
                    inputPath += ",";
                }
                inputPath += args[i];
            }
            readIDIndexPath = args[args.length - 2];
            outputPath = args[args.length - 1];
        } else {
            throw new Exception("Argument is not properly given");
        }
        
        Configuration conf = this.getConf();
        
        // configuration
        MRClusterConfiguration clusterConfig = MRClusterConfiguration.findConfiguration(clusterConfiguration);
        clusterConfig.setConfiguration(conf);

        conf.setInt(KmerIndexHelper.getConfigurationKeyOfKmerSize(), kmerSize);
        conf.setStrings(KmerIndexHelper.getConfigurationKeyOfReadIDIndexPath(), readIDIndexPath);
        
        Job job = new Job(conf, "Kmer Index Builder");
        job.setJarByClass(KmerIndexBuilder.class);

        // Identity Mapper & Reducer
        job.setMapperClass(KmerIndexBuilderMapper.class);
        job.setCombinerClass(KmerIndexBuilderCombiner.class);
        job.setPartitionerClass(KmerIndexBuilderPartitioner.class);
        job.setReducerClass(KmerIndexBuilderReducer.class);
        
        job.setMapOutputKeyClass(MultiFileCompressedSequenceWritable.class);
        job.setMapOutputValueClass(CompressedIntArrayWritable.class);
        
        // Specify key / value
        job.setOutputKeyClass(CompressedSequenceWritable.class);
        job.setOutputValueClass(CompressedIntArrayWritable.class);
        
        // Inputs
        String[] paths = FileSystemHelper.splitCommaSeparated(inputPath);
        Path[] inputFiles = FileSystemHelper.getAllFastaFilePaths(conf, paths);
        
        FileInputFormat.addInputPaths(job, FileSystemHelper.makeCommaSeparated(inputFiles));
        
        NamedOutputs namedOutputs = new NamedOutputs();
        LOG.info("Input files : " + inputFiles.length);
        for(int i=0;i<inputFiles.length;i++) {
            LOG.info("> " + inputFiles[i].toString());
            namedOutputs.addNamedOutput(inputFiles[i]);
        }
        
        job.setInputFormatClass(FastaReadInputFormat.class);

        FileOutputFormat.setOutputPath(job, new Path(outputPath));
        
        int id = 0;
        for(NamedOutput namedOutput : namedOutputs.getAllNamedOutput()) {
            LOG.info("regist new named output : " + namedOutput.getNamedOutputString());

            job.getConfiguration().setStrings(KmerIndexHelper.getConfigurationKeyOfNamedOutputName(id), namedOutput.getNamedOutputString());
            LOG.info("regist new ConfigString : " + KmerIndexHelper.getConfigurationKeyOfNamedOutputName(id));
            
            job.getConfiguration().setInt(KmerIndexHelper.getConfigurationKeyOfNamedOutputID(namedOutput.getInputString()), id);
            LOG.info("regist new ConfigString : " + KmerIndexHelper.getConfigurationKeyOfNamedOutputID(namedOutput.getInputString()));
            
            if(useBloomMap) {
                MultipleOutputs.addNamedOutput(job, namedOutput.getNamedOutputString(), BloomMapFileOutputFormat.class, CompressedSequenceWritable.class, CompressedIntArrayWritable.class);
            } else {
                MultipleOutputs.addNamedOutput(job, namedOutput.getNamedOutputString(), MapFileOutputFormat.class, CompressedSequenceWritable.class, CompressedIntArrayWritable.class);
            }
            id++;
        }
        
        job.setNumReduceTasks(clusterConfig.getReducerNumber(nodeSize));
        
        // Execute job and return status
        boolean result = job.waitForCompletion(true);

        // commit results
        commit(new Path(outputPath), conf, namedOutputs, kmerSize);
        
        return result ? 0 : 1;
    }
    
    private void commit(Path outputPath, Configuration conf, NamedOutputs namedOutputs, int kmerSize) throws IOException {
        FileSystem fs = outputPath.getFileSystem(conf);
        
        FileStatus status = fs.getFileStatus(outputPath);
        if (status.isDir()) {
            FileStatus[] entries = fs.listStatus(outputPath);
            for (FileStatus entry : entries) {
                Path entryPath = entry.getPath();
                
                // remove unnecessary outputs
                if(MapReduceHelper.isLogFiles(entryPath)) {
                    fs.delete(entryPath, true);
                } else {
                    // rename outputs
                    NamedOutput namedOutput = namedOutputs.getNamedOutputByMROutput(entryPath);
                    if(namedOutput != null) {
                        int reducerID = MapReduceHelper.getReduceID(entryPath);
                        Path toPath = new Path(entryPath.getParent(), KmerIndexHelper.getKmerIndexFileName(namedOutput.getInputString(), kmerSize, reducerID));
                        
                        LOG.info("output : " + entryPath.toString());
                        LOG.info("renamed to : " + toPath.toString());
                        fs.rename(entryPath, toPath);
                    }
                }
            }
        } else {
            throw new IOException("path not found : " + outputPath.toString());
        }
    }
}
