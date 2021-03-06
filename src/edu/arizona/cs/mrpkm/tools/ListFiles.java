package edu.arizona.cs.mrpkm.tools;

import edu.arizona.cs.mrpkm.helpers.FileSystemHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 *
 * @author iychoi
 */
public class ListFiles extends Configured implements Tool {
    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new ListFiles(), args);
        System.exit(res);
    }
    
    @Override
    public int run(String[] args) throws Exception {
        // configuration
        Configuration conf = this.getConf();
        
        String inputPath = args[0];
        
        // Inputs
        String[] paths = FileSystemHelper.splitCommaSeparated(inputPath);
        List<Path> inputFiles = new ArrayList<Path>();
        for(String pathStr : paths) {
            Path path = new Path(pathStr);
            FileSystem fs = path.getFileSystem(conf);
            inputFiles.addAll(getAllFiles(path, fs));
        }
        
        long accuSize = 0;
        int count = 0;
        for(Path input : inputFiles) {
            FileSystem fs = input.getFileSystem(conf);
            long len = fs.getFileStatus(input).getLen();
            accuSize += len;
            
            System.out.println("> " + input.toString() + " : " + len);
            count++;
        }
        
        double accuSizeKb = accuSize / 1024;
        double accuSizeMb = accuSizeKb / 1024;
        double accuSizeGb = accuSizeMb / 1024;
        
        System.out.println("Sum " + count + " files : " + accuSize);
        System.out.println("in KB " + accuSizeKb);
        System.out.println("in MB " + accuSizeMb);
        System.out.println("in GB " + accuSizeGb);
        return 0;
    }
    
    private List<Path> getAllFiles(Path path, FileSystem fs) throws IOException {
        List<Path> inputFiles = new ArrayList<Path>();
        FileStatus status = fs.getFileStatus(path);
        if(status.isDir()) {
            FileStatus[] entries = fs.listStatus(path);
            for(FileStatus entry : entries) {
                if(entry.isDir()) {
                    inputFiles.addAll(getAllFiles(entry.getPath(), fs));
                } else {
                    inputFiles.add(entry.getPath());
                }
            }
        } else {
            inputFiles.add(status.getPath());
        }
        return inputFiles;
    }
}
