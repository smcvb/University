import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.LineReader;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import types.Cluster;
import types.Point;

/**
 * Hadoop program to run the k-means algorithm
 * @author stevenb
 * @date 13-08-2013
 */
public class KMeans extends Configured implements Tool {
	
	public static enum MapCounters { // Counters used for the Map tasks
		POINTS, POINTS_COMBINED,
		TOTAL_WRITES
	}
	
	public static enum ReduceCounters { // Counters used for the Reduce tasks
		POINTS, TOTAL_READS, TOTAL_WRITES
	}
	
	public static final float CONVERGENCE_POINT = 0.01f;
	
	public static class Map extends Mapper<LongWritable, Text, IntWritable, Point> {
		
		private Cluster clusters[]; // The k clusters their mean point 
		private Point points[]; // The number of points m to cluster over k
		private HashMap<Integer, Point> partialClusterMeanMap;
		
		@Override
		public void setup(Context context) throws IOException {
			Configuration conf = context.getConfiguration();
			int emptyCluster = 0;
			String meanPath = "";
			clusters = new Cluster[conf.getInt("kmeans", 0)];
			partialClusterMeanMap = new HashMap<Integer, Point>();
			
			// Not the first iteration | May use previously found cluster means
			if (!conf.getBoolean("FirstIteration", true)) {
				//System.out.printf("SETUP: Iteration N + 1\n"); // TODO remove
				meanPath = conf.get("means");
				FileSystem fs = FileSystem.get(conf);
				for (FileStatus f : fs.listStatus(new Path(meanPath))) {
					// Read a line from the path direction
					LineReader reader = new LineReader(fs.open(f.getPath()));
					Text clusterInfo = new Text();
					reader.readLine(clusterInfo);
					//System.out.printf("SETUP: Line %s\n", clusterInfoText.toString()); // TODO REMOVE
					
					// Parse the line to a cluster
					Cluster cluster = new Cluster();
					cluster.parseCluster(clusterInfo.toString().replaceAll("[\\[\\]\\{\\}]", ""), conf.getInt("kmeans", 0));
					if (cluster.isEmpty()) {
						emptyCluster++;
					} else {
						clusters[cluster.getIndex()] = cluster;
					}
					
					// Close this file
					reader.close();
				}
				
//				for (int i = 0; i < clusters.length; i++) { // TODO REMOVE
//					if (clusters[i] != null) { // TODO REMOVE
//						System.out.printf("SETUP: Cluster Mean/Centroid:\n\t%s\n", clusters[i].toString()); // TODO REMOVE
//					} else { // TODO REMOVE
//						System.out.printf("SETUP: Empty cluster encountered on index %d\n", i); // TODO REMOVE
//					} // TODO REMOVE
//				} // TODO REMOVE
				
				if (emptyCluster > 0) {
//					System.out.printf("READ-NEW-MEANS: going to fill empty spots in cluster\n"); // TODO REMOVE
					clusters = selectNewClusters(clusters, emptyCluster);
				}
			}
		}
		
		/**
		 * Select new centroids from the outliers of
		 *  the largest cluster in the system to
		 *  become the new centers of the empty 
		 *  clusters
		 *  o input directories: one for the data points and one for the initial c
		 * @param clusters: Cluster array object containing all the clusters
		 * @param numEmptyClusters: Integer object specifying the number of empty clusters
		 * @return a Cluster array object with no empty clusters
		 */
		private Cluster[] selectNewClusters(Cluster[] clusters, int numEmptyClusters) {
			System.out.printf("SELECT_NEW_CLUSTERS: Found %d empty cluster spots, will fill these!\n", numEmptyClusters); // TODO REMOVE
			int i = 0;
			int[] emptyIndexes = new int[numEmptyClusters];
			Cluster largestCluster = new Cluster();
			for (int k = 0; k < clusters.length; k++) {
				// Find the indexes of the empty clusters
				if (clusters[k] == null) {
					emptyIndexes[i] = k;
					i++;
				} // Select the largest cluster to retrieve the outliers from to become new clusters 
				else if (clusters[k].getSize() > largestCluster.getSize()) {
					largestCluster = clusters[k];
				}
			}
			
			// Select the new cluster centroids
			Point[] outliers = largestCluster.getOutliers();
			for (int j = 0; j < numEmptyClusters; j++) {
				Cluster newCluster = new Cluster();
				newCluster.setCluster(emptyIndexes[j], 1, largestCluster.getDimensions(), outliers[j], new Point[0]);
				clusters[newCluster.getIndex()] = newCluster;
//				System.out.printf("SELECT_NEW_CLUSTERS: Filled %d with cluster %s\n", newCluster.getIndex(), newCluster.toString()); // TODO REMOVE
			}
			
			return clusters;
		}
		
		@Override
		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
			int clusterIndex = -1; // Integer representing the index number of the cluster mean the point belongs to
			double minDist = Double.MAX_VALUE, dist = 0.0;
			String[] lines = value.toString().split("\n");
			points = new Point[lines.length]; // Initialize the number of Points this mapper will work through
			Configuration conf = context.getConfiguration();
			
			// Start reading in a line and converting it to a Point object
			for (int i = 0; i < lines.length; i++) {
				//System.out.printf("MAP-READ:\n\tLine: %s\n", lines[i]); // TODO REMOVE
				points[i] = new Point(lines[i]);
				//System.out.printf("\tPoint: %s %s\n", points[i].toString(), points[i].nonNumericalValuesToString()); // TODO REMOVE
				context.getCounter(MapCounters.POINTS).increment(1);
			}
			
			// Compute the Euclidean distances between all the point/mean combinations
			for (Point point : points) {
				if (!conf.getBoolean("FirstIteration", true)) {
					for (int k = 0; k < clusters.length; k++) {
						dist = point.calculateDistance(clusters[k].getCentroid());
						//						System.out.printf("MAP-CALC: Distance: %f\n\tPoint: %s\n\tMean: %s", dist, point.toString(), clusterMeans[k].toString()); // TODO REMOVE
						if (dist < minDist) {
							minDist = dist;
							clusterIndex = k;
						}
					}
				} else { // Appoint a random cluster index to a point if it is the first iteration
					Random random = new Random();
					clusterIndex = random.nextInt(context.getConfiguration().getInt("kmeans", 0));
				}
				//System.out.printf("MAP-CALC: Point %s has MeanIndex %d\n", point.toString(), clusterIndex); // TODO REMOVE
				
				if (conf.getBoolean("combine", true)) { // In mapper combine by calculating the partial mean of the cluster
					if (partialClusterMeanMap.containsKey(clusterIndex)) {
						Point partialClusterMean = partialClusterMeanMap.get(clusterIndex);
						partialClusterMean.add(point);
						partialClusterMean.divide(2);
						partialClusterMeanMap.put(clusterIndex, partialClusterMean);
					}
					else {
						partialClusterMeanMap.put(clusterIndex, point);
					}
				}
				else { // Regular Mapper task, thus just write the found index and point
//					System.out.printf("MAP-WRITE: index: %d point: %s %s\n", clusterIndex, point.toString(), point.nonNumericalValuesToString()); // TODO REMOVE
					context.write(new IntWritable(clusterIndex), point);
					context.getCounter(MapCounters.TOTAL_WRITES).increment(1);
				}
			}
		}
		
		@Override
		public void cleanup(Context context) throws IOException, InterruptedException {
			if (context.getConfiguration().getBoolean("combine", true)) { // Can only perform a cleanup on the HashMap if the in mapper combiner was set
				context.getCounter(MapCounters.POINTS_COMBINED).increment(partialClusterMeanMap.size()); // Number of Mass messages which will be send
				context.getCounter(MapCounters.TOTAL_WRITES).increment(partialClusterMeanMap.size());
				for (Entry<Integer, Point> entry : partialClusterMeanMap.entrySet()) {
					IntWritable index = new IntWritable(entry.getKey());
					Point point = entry.getValue();
					context.write(index, point); // Write all the partial cluster means to the reducers
				}
				partialClusterMeanMap.clear(); // Empty memory
			}
		}
	}
	
	public static class Reduce extends Reducer<IntWritable, Point, IntWritable, Point> {
		
		private int size, dimensions;
		private Point recalculatedClusterMean;
		private Cluster recalculatedCluster;
		
		@Override
		public void setup(Context context) throws IOException {
			size = 0;
			dimensions = 0;
			recalculatedClusterMean = new Point();
			recalculatedCluster = new Cluster();
		}
		
		@Override
		public void reduce(IntWritable key, Iterable<Point> values, Context context) throws IOException, InterruptedException {
			for (Point value : values) { // Repeat to find the new mean
//				System.out.printf("REDUCE-READ: Point %s %s\n", value.toString(), value.nonNumericalValuesToString()); // TODO REMOVE
				if (size == 0) {
					recalculatedClusterMean.setCoordinates(value.getCoordinates()); // The first value, hence initialize the clusterMean object
					dimensions = value.getCoordinates().length; // and the dimensions for the cluster
				}
				else {
					//System.out.printf("REDUCE-CALC-ADD:\n\tNewMean: %s %s +\n\tPoint %s %s =\n", recalculatedClusterMean.toString(), recalculatedClusterMean.nonNumericalValuesToString(), value.toString(), value.nonNumericalValuesToString()); // TODO REMOVE
					recalculatedClusterMean.add(value); // Add both points together
					//System.out.printf("\t %s %s\n", recalculatedClusterMean.toString(), recalculatedClusterMean.nonNumericalValuesToString()); // TODO REMOVE
				}
				size++;
				context.write(key, value);
				context.getCounter(ReduceCounters.TOTAL_WRITES).increment(1);
				context.getCounter(ReduceCounters.TOTAL_READS).increment(1);
				context.getCounter(ReduceCounters.POINTS).increment(1);
			}
			//System.out.printf("REDUCE-CALC-DIV:\n\tNewMean: %s %s / %d =\n", recalculatedClusterMean.toString(), recalculatedClusterMean.nonNumericalValuesToString(), i); // TODO REMOVE
			recalculatedClusterMean.divide(size); // divide by 'size' to get there mean
			//System.out.printf("\t %s %s\n", recalculatedClusterMean.toString(), recalculatedClusterMean.nonNumericalValuesToString()); // TODO REMOVE
			
			// Set new cluster
			recalculatedCluster.setCluster(key.get(), size, dimensions, recalculatedClusterMean, new Point[0]);
		}
		
		@Override
		public void cleanup(Context context) throws IOException {
			Configuration conf = context.getConfiguration();
			FileSystem fs = FileSystem.get(conf);
			// Set the writer/stream
			Path meanPath = new Path(conf.get("outlierlessMeans") + "/" + conf.get("mapred.task.id"));
			FSDataOutputStream out = fs.create(meanPath, false);
			BufferedWriter bwr = new BufferedWriter(new OutputStreamWriter(out));
			// Write the cluster information
//			System.out.printf("CLEANUP: Going to write new center\n\t%s\n\tto a file...\n", recalculatedCluster.toString()); // TODO REMOVE
			bwr.write(recalculatedCluster.toString());
			// Flush and close the writer/stream
			bwr.flush();
			bwr.close();
			out.close();
		}
	}
	
	public static class MapOutliers extends Mapper<LongWritable, Text, NullWritable, NullWritable> {
		
		private boolean firstRound;
		private int index, size, dimensions;
		private Point centroid;
		private Point[] outliers;
		private Cluster recalculatedCluster;
		private Cluster[] clusters;
		
		@Override
		public void setup(Context context) throws IOException {
			Configuration conf = context.getConfiguration();
			Path meanPath = new Path(conf.get("outlierlessMeans"));
			int kmeans = conf.getInt("kmeans", 0);
			
			firstRound = true;
			index = -1;
			size = 0;
			dimensions = 0;
			centroid = new Point();
			outliers = new Point[kmeans - 1];
			clusters = new Cluster[kmeans];
			recalculatedCluster = new Cluster();
			
			//System.out.printf("SETUP: Iteration N + 1\n"); // TODO remove
			FileSystem fs = FileSystem.get(conf);
			for (FileStatus f : fs.listStatus(meanPath)) {
				// Read a line from the path direction
				LineReader reader = new LineReader(fs.open(f.getPath()));
				Text clusterInfo = new Text();
				reader.readLine(clusterInfo);
//				System.out.printf("SETUP2: Line %s\n", clusterInfo.toString()); // TODO REMOVE
				
				// Parse the line to a cluster
				Cluster cluster = new Cluster();
				cluster.parseCluster(clusterInfo.toString().replaceAll("[\\[\\]\\{\\}]", ""), conf.getInt("kmeans", 0));
				if (!cluster.isEmpty()) {
					clusters[cluster.getIndex()] = cluster;
				}
				
				// Close this file
				reader.close();
			}
		}
		
		@Override
		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
			String[] lines = value.toString().split("\n");
			for (int i = 0; i < lines.length; i++) {
				// Parse the point and select its cluster
				String[] lineTerms = lines[i].split("\\s+");
				try {
					index = (int) Double.parseDouble(lineTerms[0]);
				} catch (NumberFormatException e) {
					System.err.println("Error: expected an Integer instead of " + lineTerms[0]);
				}
				String potentialOutlierString = "";
				for (int j = 1; j < lineTerms.length; j++) {
					potentialOutlierString += lineTerms[j] + " ";
				}
				Point potentialOutlier = new Point(potentialOutlierString);
				context.getCounter(MapCounters.POINTS).increment(1); // Finished reading a point
				
				if (firstRound) { // Only need to set these in the first round
					System.out.printf("First round, thus first set\n");
					centroid = clusters[index].getCentroid();
					size = clusters[index].getSize();
					dimensions = clusters[index].getDimensions();
					firstRound = false;
				}
				
				// Calculate if the found point is an outlier of its cluster
				boolean gotPosition = false;
				double dist = potentialOutlier.calculateDistance(centroid);
				Point[] newOutliers = new Point[outliers.length];
				for (int k = 0; k < outliers.length; k++) {
					if (!gotPosition) {
						newOutliers[k] = outliers[k];
						if (newOutliers[k] == null || outliers[k].calculateDistance(centroid) < dist) {
							newOutliers[k] = new Point(potentialOutlier.getCoordinates(), new String[0]);
							gotPosition = true;
						}
					} else {
						newOutliers[k] = outliers[k - 1];
					}
				}
				outliers = Arrays.copyOf(newOutliers, newOutliers.length);
			}
		}
		
		@Override
		public void cleanup(Context context) throws IOException {
			// Set the cluster again, but now with outliers
			recalculatedCluster.setCluster(index, size, dimensions, centroid, outliers);
//			System.out.printf("MAPOUTLIERS: Just set the new cluster\n"); // TODO REMOVE
			
			Configuration conf = context.getConfiguration();
			FileSystem fs = FileSystem.get(conf);
			// Set the writer/stream
			Path meanPath = new Path(conf.get("means") + "/" + conf.get("mapred.task.id"));
			FSDataOutputStream out = fs.create(meanPath, false);
			BufferedWriter bwr = new BufferedWriter(new OutputStreamWriter(out));
			
			// Write the cluster information
//			System.out.printf("CLEANUP2: Going to write new center\n\t%s\n\tto a file...\n", recalculatedCluster.toString()); // TODO REMOVE
			bwr.write(recalculatedCluster.toString());
			
			// Flush and close the writer/stream
			bwr.flush();
			bwr.close();
			out.close();
		}
	}
	
	/**
	 * Check whether the currents cluster means
	 *  equals the previous clusters means
	 * @param previousClusters: Point array containing the previous
	 * 	found cluster means
	 * @param currentClusters: Point array containing the current
	 * 	found cluster means
	 * @return True: in case all the means equal
	 * 	False: in case one or more of the cluster means
	 * 		do not equal
	 */
	private boolean checkConvergence(Cluster[] previousClusters, Cluster[] currentClusters) {
//		System.out.printf("CONVERGENCE: Started to check for convergence...\n"); // TODO REMOVE
		if (previousClusters.length == 0) { // if this is the first iteration/checkConvergence()  method call, then its length will be zero
//			System.out.printf("CONVERGENCE: no previous cluster, so false\n"); // TODO REMOVE
			return false;
		}
		for (int k = 0; k < currentClusters.length; k++) {
			if (previousClusters[k] == null || currentClusters[k] == null) { // Found an empty mean, hence an empty cluster
//				System.out.printf("CONVERGENCE: empty cluster in previous set or current set, hence false\n"); // TODO REMOVE
				return false; // Thus cannot check for convergence
			}
		}
		
		double cmp = 0.0;
		for (int k = 0; k < previousClusters.length; k++) {
//			System.out.printf("CONVERGENCE:\n\tPre: %s\n\tCur: %s\n", previousClusters[k].toString(), currentClusters[k].toString()); // TODO REMOVE
			cmp += previousClusters[k].getCentroid().compareTo(currentClusters[k].getCentroid(), CONVERGENCE_POINT);
			if (cmp != 0) {
				return false;
			}
		}
		return true;
	}
	
	/**
	 * Retrieve the recalculated cluster means from the reduce job
	 *  for sending them through convergence checking
	 * @param conf: A Configuration object containing the Map/Reduce 
	 * 	job configurations
	 * @param kmeans: Number of clusters means k as an integer
	 * @return: A Point array object containing the recalculated
	 * 	cluster means
	 * @throws IOException for errors in a FileSystem operation
	 */
	private Cluster[] getClusters(Configuration conf, int kmeans) throws IOException {
		boolean done = false;
		int retry = 0;
		Cluster[] clusters = new Cluster[kmeans];
		FileSystem fs = FileSystem.get(conf);
		
		for (FileStatus f : fs.listStatus(new Path(conf.get("means")))) {
			while (!done && retry < 5) {
				try {
					// Read a line from the path direction
					LineReader reader = new LineReader(fs.open(f.getPath()));
					Text clusterInfo = new Text();
					reader.readLine(clusterInfo);
//					System.out.printf("GETCLUSTERS: Line %s\n", clusterInfo.toString()); // TODO REMOVE
					
					// Read in a new cluster
					Cluster cluster = new Cluster();
					cluster.parseCluster(clusterInfo.toString().replaceAll("[\\[\\]\\{\\}]", ""), kmeans);
					if (!cluster.isEmpty()) {
						clusters[cluster.getIndex()] = cluster;
					}
					
					// Close this file
					reader.close();
					done = true;
				} catch (EOFException e) { // In case of failure, retry to read the file for a max. of 5 times
					System.out.printf("Unsuccesfully read a file (%s) | will retry...\n", e);
					done = false;
					retry++;
				}
			}
			done = false; // Reset done to false for next round
			if (retry >= 5) {
				System.out.printf("\nUnsuccesfully read a file for five times | File has been skipped\n\n");
				retry = 0; // Reset retry to zero for next round
			}
		}
		
		return clusters;
	}
	
	private Cluster[] phase2(Configuration conf, String baseInputString, int kmeans, int iteration) throws IOException, InterruptedException, ClassNotFoundException {
		String inputString = baseInputString + "/iter" + (iteration + 1);
		conf.set("outlierlessMeans", inputString + "means/phase1"); // the path where to store the cluster means found
		conf.set("means", inputString + "means/phase2"); // the path where to store the cluster means found
		
		Job phase2 = new Job(conf, "KMeans - Iteration " + (iteration + 1) + ", Phase 2"); // Main settings
		phase2.setJarByClass(KMeans.class);
		FileInputFormat.setInputPaths(phase2, new Path(inputString)); // Input settings
		phase2.setInputFormatClass(TextInputFormat.class);
		phase2.setOutputFormatClass(NullOutputFormat.class);
		phase2.setOutputKeyClass(NullWritable.class);
		phase2.setOutputValueClass(NullWritable.class);
		phase2.setMapperClass(MapOutliers.class); // Class settings
		phase2.setNumReduceTasks(0); // Set to zero, since no reduce tasks should be started
		
		long startTime = System.currentTimeMillis();
		if (phase2.waitForCompletion(true)) {
			System.out.println("\n\nIteration " + (iteration + 1) + ", Phase 2 Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
		}
		
		return getClusters(conf, kmeans);
	}
	
	/**
	 * Run an iteration of the k-means clustering algorithm as a
	 *  Map/Reduce job
	 * @param conf: Configuration object used for every Map/Reduce job initiated 
	 * @param inputString: the input path as a String
	 * @param outputString: the output path as a String
	 * @param kmeans: the number of clusters to create as an Integer
	 * @param iterations: the number of iterations to run the algorithm as an Integer
	 * @return: a Point array, containing all the new centroids found
	 * @throws IOException for creating and starting a job, setting the input path
	 * 				and checking convergence
	 * @throws InterruptedException for starting a job
	 * @throws ClassNotFoundException for starting a job
	 */
	public void phase1(Configuration conf, String inputString, String baseOutputString, int kmeans, int iteration) throws IOException, InterruptedException, ClassNotFoundException {
		String outputString = baseOutputString + "/iter" + (iteration + 1);
		conf.set("outlierlessMeans", outputString + "means/phase1"); // the path where to store the cluster means found
		conf.set("means", baseOutputString + "/iter" + iteration + "means/phase2"); // the path where to store the cluster means found
		
		Job phase1 = new Job(conf, "KMeans - Iteration " + (iteration + 1) + ", Phase 1"); // Main settings
		phase1.setJarByClass(KMeans.class);
		FileInputFormat.setInputPaths(phase1, new Path(inputString)); // Input settings
		phase1.setInputFormatClass(TextInputFormat.class);
		FileOutputFormat.setOutputPath(phase1, new Path(outputString)); // Output settings
		phase1.setOutputFormatClass(TextOutputFormat.class);
		phase1.setOutputKeyClass(IntWritable.class);
		phase1.setOutputValueClass(Point.class);
		phase1.setMapperClass(Map.class); // Class settings
		phase1.setReducerClass(Reduce.class);
		phase1.setNumReduceTasks(kmeans);
		
		long startTime = System.currentTimeMillis();
		if (phase1.waitForCompletion(true)) {
			System.out.println("\n\nIteration " + (iteration + 1) + ", Phase 1 Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
		}
	}
	
	/**
	 * Iterate the main map/reduce job of:
	 *  1. Calculating distances between cluster centroids and the points
	 *  2. Recalculating the new cluster centroids
	 * If it is the first iteration, start by setting a flag in the 
	 * 	Configuration object 
	 * After a round was finished, check if the centroids converged (e.g. they equal the previous round)
	 * @param conf: Configuration object used for every Map/Reduce job initiated 
	 * @param inputString: the input path as a String
	 * @param baseOutputString: the base path as a String to put all the output in
	 * @param kmeans: the number of clusters to create as an Integer
	 * @param iterations: the number of iterations to run the algorithm as an Integer
	 * @throws Exception from the phase1() method
	 */
	private void iterate(Configuration conf, String inputString, String baseOutputString, int kmeans, int iterations) throws Exception {
		boolean converged = false;
		Cluster[] previousClusters = new Cluster[0], currentClusters = new Cluster[kmeans];
		
		System.out.printf("Matrix Input Path: %s Base Output Path: %s Iterations: %d\n", inputString, baseOutputString, iterations);
		for (int i = 0; i < iterations; i++) {
			// Check whether first iteration; of concern for initialization process
			if (i == 0) {
				conf.setBoolean("FirstIteration", true);
			}
			else {
				conf.setBoolean("FirstIteration", false);
			}
			
			// Start main algorithm
			System.out.printf("Phase 1, Iteration %d will start\n\n;", i + 1);
			phase1(conf, inputString, baseOutputString, kmeans, i); // Main k-means algorithm
			System.out.printf("\n\nPhase 1, Iteration %d complete | Phase 2, Iteration %d will start\n\n", i + 1, i + 1);
			currentClusters = phase2(conf, baseOutputString, kmeans, i); // Find the outliers for possible empty clusters
			System.out.printf("\n\nPhase 2, Iteration %d complete\n", i + 1);
			
			// Check for convergence of the algorithm
			if (checkConvergence(previousClusters, currentClusters)) {
				System.out.printf("Clusters Converged in Iteration %d\n\n", i + 1);
				for (int k = 0; k < currentClusters.length; k++) {
					System.out.printf("Cluster %d:\t%s\n", k, currentClusters[k].toString());
				}
				converged = true; // set to unset the final 'we-did-not-converge' print 
				break;
			}
			else {
				previousClusters = currentClusters;
			}
		}
		
		if (!converged) {
			System.out.printf("Clusters did not converge, but reached the maximum number of iterations\n\n");
			for (int k = 0; k < currentClusters.length; k++) {
				System.out.printf("Cluster %d:\t%s\n", k, currentClusters[k].toString());
			}
		}
	}
	
	/**
	 * Prints out the usages of this program in case the user
	 *  gave incorrect input
	 * @param numArgs: number of arguments in the String array object
	 */
	private int printUsage() {
		System.out.println("usage:\t <input path> <output path> <k mean points> <number of iterations> <[OPTIONAL] add 'combine' to use inmapper combiner>");
		ToolRunner.printGenericCommandUsage(System.out);
		return -1;
	}
	
	/**
	 * Runs the main program
	 * 
	 * @param args: String array of arguments given at start 
	 * @return -1 in case of error | 0 in case of success
	 * @throws Exception from the iterate() method
	 */
	@Override
	public int run(String[] args) throws Exception {
		int iterations = 0, kmeans = 0;
		String inputString = "", outputString = "";
		Configuration conf = new Configuration(getConf());
		
		// Set arguments
		if (args.length < 4) {
			System.err.println("Error: too few parameters given");
			return printUsage();
		}
		inputString = args[0];
		outputString = args[1];
		try {
			kmeans = Integer.parseInt(args[2]);
			conf.setInt("kmeans", kmeans);
			iterations = Integer.parseInt(args[3]);
		} catch (NumberFormatException e) {
			System.err.println("Error: expected Integers instead of " + args[2] + " (arg 2) and " + args[3] + " (arg 3)");
			return printUsage();
		}
		if (args.length > 4 && args[4].equals("combine")) {
			conf.setBoolean("combine", true);
		}
		else {
			conf.setBoolean("combine", false);
		}
		
		// Create and start iterations
		iterate(conf, inputString, outputString, kmeans, iterations);
		return 0;
	}
	
	public static void main(String[] args) throws Exception {
		int result = ToolRunner.run(new Configuration(), new KMeans(), args);
		System.exit(result);
	}
}
