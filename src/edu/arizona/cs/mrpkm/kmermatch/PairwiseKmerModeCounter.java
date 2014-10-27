package edu.arizona.cs.mrpkm.kmermatch;

import edu.arizona.cs.mrpkm.kmerrangepartitioner.KmerRangePartitioner;
import edu.arizona.cs.hadoop.fs.irods.HirodsFileSystem;
import edu.arizona.cs.hadoop.fs.irods.output.HirodsFileOutputFormat;
import edu.arizona.cs.hadoop.fs.irods.output.HirodsMultipleOutputs;
import edu.arizona.cs.hadoop.fs.irods.output.HirodsTextOutputFormat;
import edu.arizona.cs.mrpkm.cluster.AMRClusterConfiguration;
import edu.arizona.cs.mrpkm.cluster.MRClusterConfiguration_default;
import edu.arizona.cs.mrpkm.kmeridx.KmerIndexHelper;
import edu.arizona.cs.mrpkm.notification.EmailNotification;
import edu.arizona.cs.mrpkm.notification.EmailNotificationException;
import edu.arizona.cs.mrpkm.types.MultiFileReadIDWritable;
import edu.arizona.cs.mrpkm.namedoutputs.NamedOutput;
import edu.arizona.cs.mrpkm.namedoutputs.NamedOutputs;
import edu.arizona.cs.mrpkm.utils.FileSystemHelper;
import edu.arizona.cs.mrpkm.utils.MapReduceHelper;
import edu.arizona.cs.mrpkm.utils.MultipleOutputsHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 *
 * @author iychoi
 */
public class PairwiseKmerModeCounter extends Configured implements Tool {
    
    private static final Log LOG = LogFactory.getLog(PairwiseKmerModeCounter.class);
    
    private static class PairwiseKmerModeCounter_Cmd_Args {
        
        private static final int DEFAULT_PARTITIONS = 1000;
        private static final int PARTITIONS_PER_CORE = 10;
        
        @Option(name = "-h", aliases = "--help", usage = "print this message") 
        private boolean help = false;
        
        private AMRClusterConfiguration cluster = new MRClusterConfiguration_default();
        
        @Option(name = "-c", aliases = "--cluster", usage = "specify cluster configuration")
        public void setCluster(String clusterConf) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
            this.cluster = AMRClusterConfiguration.findConfiguration(clusterConf);
        }
        
        @Option(name = "-n", aliases = "--nodenum", usage = "specify the number of hadoop slaves")
        private int nodes = 1;
        
        private int partitions = DEFAULT_PARTITIONS;
        private boolean partitionsGivenByUser = false;
        
        @Option(name = "-p", aliases = "--partitions", usage = "specify the number of partitions a hadoop scheduler will split input files into")
        public void setPartitions(int partitions) {
            partitionsGivenByUser = true;
            this.partitions = partitions;
        }
        
        private KmerRangePartitioner.PartitionerMode partitionerMode = KmerRangePartitioner.PartitionerMode.MODE_EQUAL_ENTRIES;
        
        @Option(name = "--partitionermode", usage = "specify partitioner's work mode")
        public void setPartitionerMode(String mode) {
            if(mode == null) {
                this.partitionerMode = KmerRangePartitioner.PartitionerMode.MODE_EQUAL_ENTRIES;
            } else if(mode.equalsIgnoreCase("entries")) {
                this.partitionerMode = KmerRangePartitioner.PartitionerMode.MODE_EQUAL_ENTRIES;
            } else if(mode.equalsIgnoreCase("range")) {
                this.partitionerMode = KmerRangePartitioner.PartitionerMode.MODE_EQUAL_RANGE;
            } else {
                this.partitionerMode = KmerRangePartitioner.PartitionerMode.valueOf(mode);
                if(this.partitionerMode == null) {
                    this.partitionerMode = KmerRangePartitioner.PartitionerMode.MODE_EQUAL_ENTRIES;
                }
            }
        }
        
        @Option(name = "--min", usage = "specify the minimum bound of hit")
        private int minHit = 0;
        
        @Option(name = "--max", usage = "specify the maximum bound of hit")
        private int maxHit = 0;
        
        @Option(name = "--notifyemail", usage = "specify email address for job notification")
        private String notificationEmail;
        
        @Option(name = "--notifypassword", usage = "specify email password for job notification")
        private String notificationPassword;
        
        @Argument(metaVar = "input-path [input-path ...] output-path", usage = "input-paths and output-path")
        private List<String> paths = new ArrayList<String>();
        
        public boolean isHelp() {
            return this.help;
        }
        
        public AMRClusterConfiguration getConfiguration() {
            return this.cluster;
        }
        
        public int getNodes() {
            return this.nodes;
        }
        
        public int getPartitions() {
            return this.partitions;
        }
        
        public int getPartitions(int cores) {
            if(this.partitionsGivenByUser) {
                return this.partitions;
            } else {
                return cores * PARTITIONS_PER_CORE;
            }
        }
        
        public KmerRangePartitioner.PartitionerMode getPartitionerMode() {
            return this.partitionerMode;
        }
        
        public int getMinHit() {
            return this.minHit;
        }
        
        public int getMaxHit() {
            return this.maxHit;
        }
        
        public String getOutputPath() {
            if(this.paths.size() > 1) {
                return this.paths.get(this.paths.size()-1);
            }
            
            return null;
        }
        
        public String[] getInputPaths() {
            if(this.paths.isEmpty()) {
                return new String[0];
            }
            
            String[] inpaths = new String[this.paths.size()-1];
            for(int i=0;i<this.paths.size()-1;i++) {
                inpaths[i] = this.paths.get(i);
            }
            
            return inpaths;
        }
        
        public String getCommaSeparatedInputPath() {
            String[] inputPaths = getInputPaths();
            StringBuilder CSInputPath = new StringBuilder();
            for(String inputpath : inputPaths) {
                if(CSInputPath.length() != 0) {
                    CSInputPath.append(",");
                }
                
                CSInputPath.append(inputpath);
            }
            
            return CSInputPath.toString();
        }
        
        public boolean needNotification() {
            return (notificationEmail != null);
        }
        
        public String getNotificationEmail() {
            return notificationEmail;
        }
        
        public String getNotificationPassword() {
            return notificationPassword;
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for(String arg : this.paths) {
                if(sb.length() != 0) {
                    sb.append(", ");
                }
                
                sb.append(arg);
            }
            
            return "help = " + this.help + "\n" +
                    "paths = " + sb.toString();
        }
        
        public boolean checkValidity() {
            if(this.cluster == null || 
                    this.nodes <= 0 ||
                    this.partitions <= 0 ||
                    this.minHit < 0 ||
                    this.maxHit < 0 ||
                    this.paths == null || this.paths.isEmpty() ||
                    this.paths.size() < 2) {
                return false;
            }
            return true;
        }
    }
    
    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new PairwiseKmerModeCounter(), args);
        System.exit(res);
    }
    
    @Override
    public int run(String[] args) throws Exception {
        // parse command line
        PairwiseKmerModeCounter_Cmd_Args cmdargs = new PairwiseKmerModeCounter_Cmd_Args();
        CmdLineParser parser = new CmdLineParser(cmdargs);
        CmdLineException parseException = null;
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // handling of wrong arguments
            parseException = e;
        }
        
        if(cmdargs.isHelp() || !cmdargs.checkValidity()) {
            parser.printUsage(System.err);
            return 1;
        }
        
        if(parseException != null) {
            System.err.println(parseException.getMessage());
            parser.printUsage(System.err);
            return 1;
        }
        
        AMRClusterConfiguration clusterConfig = cmdargs.getConfiguration();
        int nodeSize = cmdargs.getNodes();
        int partitionNum = cmdargs.getPartitions(nodeSize * clusterConfig.getCoresPerMachine());
        KmerRangePartitioner.PartitionerMode partitionerMode = cmdargs.getPartitionerMode();
        int matchFilterMin = cmdargs.getMinHit();
        int matchFilterMax = cmdargs.getMaxHit();
        String inputPath = cmdargs.getCommaSeparatedInputPath();
        String outputPath = cmdargs.getOutputPath();
        
        // configuration
        Configuration conf = this.getConf();
        clusterConfig.configureClusterParamsTo(conf);
        
        conf.setEnum(KmerMatchHelper.getConfigurationPartitionerMode(), partitionerMode);
        conf.setInt(PairwiseKmerModeCounterHelper.getConfigurationKeyOfMatchFilterMin(), matchFilterMin);
        conf.setInt(PairwiseKmerModeCounterHelper.getConfigurationKeyOfMatchFilterMax(), matchFilterMax);

        Job job = new Job(conf, "Pairwise Kmer Mode Counter");
        job.setJarByClass(PairwiseKmerModeCounter.class);

        // Identity Mapper & Reducer
        job.setMapperClass(PairwiseKmerModeCounterMapper.class);
        job.setPartitionerClass(PairwiseKmerModeCounterPartitioner.class);
        job.setReducerClass(PairwiseKmerModeCounterReducer.class);
        
        job.setMapOutputKeyClass(MultiFileReadIDWritable.class);
        job.setMapOutputValueClass(IntWritable.class);
        
        // Specify key / value
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        
        // Inputs
        String[] paths = FileSystemHelper.splitCommaSeparated(inputPath);
        Path[] inputFiles = FileSystemHelper.getAllKmerIndexFilePaths(job.getConfiguration(), paths);
        
        for(Path path : inputFiles) {
            LOG.info("Input : " + path);
        }
        
        // check kmerSize
        int kmerSize = -1;
        for(Path indexPath : inputFiles) {
            if(kmerSize <= 0) {
                kmerSize = KmerIndexHelper.getKmerSize(indexPath);
            } else {
                if(kmerSize != KmerIndexHelper.getKmerSize(indexPath)) {
                    throw new Exception("kmer sizes of given index files are different");
                }
            }
        }
        
        if(kmerSize <= 0) {
            throw new Exception("kmer size is not properly set");
        }
        
        KmerMatchInputFormat.addInputPaths(job, FileSystemHelper.makeCommaSeparated(inputFiles));
        KmerMatchInputFormat.setKmerSize(job, kmerSize);
        KmerMatchInputFormat.setNumPartitions(job, partitionNum);
        
        NamedOutputs namedOutputs = new NamedOutputs();
        Path[][] groups = KmerIndexHelper.groupKmerIndice(inputFiles);
        LOG.info("Input index groups : " + groups.length);
        for(int i=0;i<groups.length;i++) {
            Path[] group = groups[i];
            LOG.info("Input index group " + i + " : " + group.length);
            for(int j=0;j<group.length;j++) {
                LOG.info("> " + group[j].toString());
            }
        }
        
        for(int i=0;i<groups.length;i++) {
            Path[] thisGroup = groups[i];
            for(int j=0;j<groups.length;j++) {
                Path[] thatGroup = groups[j];
                if(i != j) {
                    namedOutputs.addNamedOutput(PairwiseKmerModeCounterHelper.getPairwiseModeCounterOutputName(KmerIndexHelper.getFastaFileName(thisGroup[0]), KmerIndexHelper.getFastaFileName(thatGroup[0])));
                }
            }
        }
        
        job.setInputFormatClass(KmerMatchInputFormat.class);

        Path outputHadoopPath = new Path(outputPath);
        FileSystem outputFileSystem = outputHadoopPath.getFileSystem(job.getConfiguration());
        if(outputFileSystem instanceof HirodsFileSystem) {
            LOG.info("Use H-iRODS");
            HirodsFileOutputFormat.setOutputPath(job, outputHadoopPath);
            job.setOutputFormatClass(HirodsTextOutputFormat.class);
            MultipleOutputsHelper.setMultipleOutputsClass(job.getConfiguration(), HirodsMultipleOutputs.class);
        } else {
            FileOutputFormat.setOutputPath(job, outputHadoopPath);
            job.setOutputFormatClass(TextOutputFormat.class);
            MultipleOutputsHelper.setMultipleOutputsClass(job.getConfiguration(), MultipleOutputs.class);
        }
        
        int id = 0;
        for(NamedOutput namedOutput : namedOutputs.getAllNamedOutput()) {
            LOG.info("regist new named output : " + namedOutput.getNamedOutputString());

            job.getConfiguration().setStrings(PairwiseKmerModeCounterHelper.getConfigurationKeyOfNamedOutputName(id), namedOutput.getNamedOutputString());
            LOG.info("regist new ConfigString : " + PairwiseKmerModeCounterHelper.getConfigurationKeyOfNamedOutputName(id));
            
            job.getConfiguration().setInt(PairwiseKmerModeCounterHelper.getConfigurationKeyOfNamedOutputID(namedOutput.getInputString()), id);
            LOG.info("regist new ConfigString : " + PairwiseKmerModeCounterHelper.getConfigurationKeyOfNamedOutputID(namedOutput.getInputString()));
            
            if(outputFileSystem instanceof HirodsFileSystem) {
                HirodsMultipleOutputs.addNamedOutput(job, namedOutput.getNamedOutputString(), HirodsTextOutputFormat.class, Text.class, Text.class);
            } else {
                MultipleOutputs.addNamedOutput(job, namedOutput.getNamedOutputString(), TextOutputFormat.class, Text.class, Text.class);
            }
            id++;
        }
        
        job.setNumReduceTasks(clusterConfig.getPairwiseKmerModeCounterReducerNumber(nodeSize));
        
        // Execute job and return status
        boolean result = job.waitForCompletion(true);

        // commit results
        if(result) {
            commit(new Path(outputPath), job.getConfiguration(), namedOutputs, kmerSize);
        }
        
        // notify
        if(cmdargs.needNotification()) {
            EmailNotification emailNotification = new EmailNotification(cmdargs.getNotificationEmail(), cmdargs.getNotificationPassword());
            emailNotification.addJob(job);
            try {
                emailNotification.send();
            } catch(EmailNotificationException ex) {
                LOG.error(ex);
            }
        }
        
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
                        int mapreduceID = MapReduceHelper.getMapReduceID(entryPath);
                        Path toPath = new Path(entryPath.getParent(), namedOutput.getInputString() + ".PKM." + mapreduceID);
                        
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
