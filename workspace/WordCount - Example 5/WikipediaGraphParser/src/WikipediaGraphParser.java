import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

import types.LongArrayWritable;
import cloud9.WikipediaPage;
import cloud9.WikipediaPageInputFormat;

public class WikipediaGraphParser {
	
	public static final int INITIAL_CAPACITY = 1707210; //Initial-Capacity = (Number-of-Wikipedia-pages / 0.75) + 1
	public static final String MAPPINGFILE = "mappingLowercase"; //File the path should be equal to
	
	public static class Map extends Mapper<LongWritable, WikipediaPage, Text, LongArrayWritable> {
		
		private HashMap<String, Long> title_docidMap = null;
		
		@Override
		public void setup(Context context) {
			try {
				Path[] paths = DistributedCache.getLocalCacheFiles(context.getConfiguration());
				if (paths != null && paths.length > 0)
					loadTitleDocidMapping(context, paths[0]);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		private void loadTitleDocidMapping(Context context, Path path) {
			BufferedReader br = null;
			long counter = 0;
			
			try {
				br = new BufferedReader(new FileReader(path.toString()));
			} catch (FileNotFoundException e1) {
				e1.printStackTrace();
				System.out.println("read from distributed cache: file not found!");
			}
			
			try {
				String line = "";
				title_docidMap = new HashMap<String, Long>(INITIAL_CAPACITY);
				while ((line = br.readLine()) != null) {
					String[] arr = line.split("\t");
					if (arr.length == 2) {
						counter++;
						context.setStatus("Progressing:" + counter);
						context.progress();
						title_docidMap.put(arr[0], Long.parseLong(arr[1]));
					}
				}
			} catch (IOException e1) {
				e1.printStackTrace();
				System.out.println("read from distributed cache: read length and instances");
			}
		}
		
		@Override
		public void map(LongWritable key, WikipediaPage value, Context context) throws IOException, InterruptedException {
			if (title_docidMap == null || value.isEmpty())
				return;
			Text docid = new Text(value.getDocid() + " 1");//'1' is the initial pagerank
			List<String> allLinksList = value.extractLinkDestinations();
			Iterator<String> linkIterator = allLinksList.iterator();
			ArrayList<LongWritable> linksList = new ArrayList<LongWritable>();
			
			while (linkIterator.hasNext()) {
				String link = linkIterator.next().toLowerCase();//Should be lowercase to make sure a correct comparison is made | HashMap contains lowercase keys
				if (title_docidMap.containsKey(link)) {
					LongWritable linkDocid = new LongWritable(title_docidMap.get(link));
					if (!linksList.contains(linkDocid))
						linksList.add(linkDocid);
				}
			}
			
			LongWritable[] linksArray = new LongWritable[linksList.size()];
			linksList.toArray(linksArray);
			LongArrayWritable links = new LongArrayWritable(LongWritable.class);
			links.set(linksArray);
			context.write(docid, links);
		}
	}
	
	private static void printUsage(String[] args) {
		if (args.length < 3) {
			System.out.println("usage:\t <input path> <output path> <title-docid mapping path>");
			System.exit(-1);
		}
	}
	
	public static Job createJob(String[] args, Configuration conf) throws IOException, URISyntaxException {
		printUsage(args);
		conf.set("mapping.path", args[2]);
		conf.set("mapred.child.java.opts", "-Xmx4096m");
		conf.set("mapred.task.timeout", "0");
		conf.set("wiki.language", "en");
		
		Path filepath = new Path(args[2]);
		FileSystem fs = FileSystem.get(filepath.toUri(), conf);
		filepath = fs.makeQualified(filepath);
		DistributedCache.addCacheFile(filepath.toUri(), conf);
		
		Job job = new Job(conf, "Wikipedia Graph Parser");
		job.setJarByClass(WikipediaGraphParser.class);
		FileInputFormat.setInputPaths(job, new Path(args[0])); //Input settings
		job.setInputFormatClass(WikipediaPageInputFormat.class);
		FileOutputFormat.setOutputPath(job, new Path(args[1])); //Ouput settings
		job.setOutputFormatClass(TextOutputFormat.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(LongArrayWritable.class);
		job.setMapperClass(Map.class); //Class settings
		job.setNumReduceTasks(0);//If set to zero, Reducer is ignored and Mapper will be the output
		
		return job;
	}
	
	public static void main(String[] args) throws Exception {
		Configuration conf = new Configuration();
		Job job = createJob(args, conf);
		
		long startTime = System.currentTimeMillis();
		if (job.waitForCompletion(true))
			System.out.println("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
	}
}
