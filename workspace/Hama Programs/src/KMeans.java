import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.TextInputFormat;
import org.apache.hama.bsp.TextOutputFormat;
import org.apache.hama.bsp.sync.SyncException;

import types.Cluster;
import types.ClusterMessage;
import types.Point;

/**
 * Hama program to run the k-means algorithm
 * @author stevenb
 * @date 14-08-2013
 */
public class KMeans extends Configured implements Tool {
	
	public static final float CONVERGENCE_POINT = 0.01f;
	public static final String POINT = "POINT";
	
	public static class KMeansBSP extends BSP<LongWritable, Text, IntWritable, Text, ClusterMessage> {
		
		private int kmeans, round, iterations;
		private Cluster me;
		private ArrayList<Point> points;
		private ConcurrentHashMap<String, Cluster> clusters;
		
		private String peerName; // TODO REMOVE
		
		@Override
		public void setup(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException { //initialize
			kmeans = peer.getNumPeers();
			round = 0;
			iterations = peer.getConfiguration().getInt("iterations", 0);
			me = new Cluster(peer.getPeerIndex(), 0, 0, new Point(), new Point[kmeans - 1]);
			points = new ArrayList<Point>();
			clusters = new ConcurrentHashMap<String, Cluster>(kmeans);
			
			peerName = peer.getPeerName(); // TODO REMOVE
		}
		
		@Override
		/**
		 * The KMeans Clustering algorithm
		 */
		public void bsp(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException, InterruptedException, SyncException {
			boolean converged = false;
			initialize(peer); // Map
			peer.sync();
			while (!converged && round < iterations) {
				System.out.println("\n:start: " + peerName + "-" + round + " cluster: " + me.toString()); //TODO REMOVE
				
				if (round != 0) {
					assignPoints(peer); // Map
					peer.sync();
				}
				receiveMessages(peer); // Receive the newly given points
				
				updateCluster(peer); // Update this cluster its info, since you now have new points | involves new mean, new outliers, and size setting
				peer.sync(); // Sync after second set-outlier phase
				converged = receiveMessages(peer); // Set the new clusters and check if converged...
				
				System.out.println(":end: " + peerName + "-" + round + " cluster: " + me.toString() + "\n"); //TODO REMOVE
				round++;
			}
			
			System.out.printf("\n\n");
			if (peer.getPeerIndex() == 0 && converged) {
				System.out.printf("Clusters Converged in Iteration %d\n\n", round);
				for (Entry<String, Cluster> entry : clusters.entrySet()) {
					System.out.printf("Cluster:\t%s\n", entry.getValue().toString());
				}
			} else if (peer.getPeerIndex() == 0) {
				System.out.printf("Clusters did not converge, but reached the maximum number of iterations\n\n");
				for (Entry<String, Cluster> entry : clusters.entrySet()) {
					System.out.printf("Cluster:\t%s\n", entry.getValue().toString());
				}
			}
		}
		
		/**
		 * The first round of KMeans, hence first need to initialize
		 *  all the point to a cluster.
		 * Points are assigned randomly to a cluster (Random Partitioning)
		 *  within this program.
		 * @param peer: a BSPPeer object containing all the information about 
		 * 	this BSPPeer task.
		 * @throws IOException from the readNext() and send() methods.
		 */
		private void initialize(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException {
//			System.out.println(":initialize: " + peerName + "-" + round); //TODO REMOVE
			LongWritable key = new LongWritable();
			Text value = new Text();
			String[] lines = null;
			
			while (peer.readNext(key, value)) {
				lines = value.toString().split("\n");
				for (int i = 0; i < lines.length; i++) {
					Random random = new Random();
					int clusterIndex = random.nextInt(kmeans);
					Cluster point = new Cluster(-1, -1, -1, new Point(lines[i]), new Point[0]);
					
					ClusterMessage m = new ClusterMessage(POINT, point);
					String name = peer.getPeerName(clusterIndex);
					peer.send(name, m);
				}
			}
		}
		
		/**
		 * Receive all the messages send by other peers at this peer.
		 * Depending on the tag contained in the ClusterMessage object,
		 *  one can see whether it is a new point or a cluster centroid
		 *  message.
		 * @param peer: a BSPPeer object containing all the information about 
		 * 	this BSPPeer task.
		 * @return true or false depending on whether the algorithm converged
		 * @throws IOException from the getCurrentMessage() method.
		 */
		private boolean receiveMessages(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException {
//			System.out.println(":receivemessages: " + peerName + "-" + round); //TODO REMOVE
			boolean convergence = false;
			int numberOfMessages = peer.getNumCurrentMessages(), received = 0; // TODO REMOVE
			HashMap<String, Cluster> newClusters = new HashMap<String, Cluster>(kmeans);
			
//			System.out.println(":receivemessages: " + peerName + "-" + round + "| have " + points.size() + " points"); //TODO REMOVE
			
			for (int i = 0; i < numberOfMessages; i++) {
				ClusterMessage message = peer.getCurrentMessage();
				String tag = message.getTag();
				if (tag.equals(POINT)) { // Received a Point message
					points.add(message.getCluster().getCentroid());
					received++; // TODO REMOVE
				} else { // Probably a Cluster mean
//					System.out.println(":receivemessages: " + peerName + "-" + round + "| " + tag + " received cluster" + message.getCluster()); //TODO REMOVE
					newClusters.put(tag, message.getCluster());
//					System.out.println(":receivemessages: " + peerName + "-" + round + "| " + tag + " held cluster" + clusters.get(tag)); //TODO REMOVE
				}
			}
			
//			if(received > 0){//TODO REMOVE
//				System.out.println(":receivemessages: " + peerName + "-" + round + "| received " + received + " points"); //TODO REMOVE
//			}//TODO REMOVE
			if (newClusters.size() > 0) { // Only if any clusters are received, can we check for convergence and set the new clusters
				convergence = checkConvergence(newClusters);
				setNewClusters(newClusters);
			}
			return convergence;
		}
		
		/**
		 * Check whether a point contained in this cluster is 
		 *  closer to another clusters its mean. If so, assign 
		 *  that point to that clusters and remove it from your own.
		 * @param peer: a BSPPeer object containing all the information about 
		 * 	this BSPPeer task.
		 * @throws IOException from the send() method
		 */
		private void assignPoints(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException {
//			System.out.println(":assignpoints: " + peerName + "-" + round); //TODO REMOVE
			int send = 0; // TODO REMOVE
			double dist = 0.0, minDist = Double.MAX_VALUE;
			String name = "";
			String[] clusterNames = peer.getAllPeerNames();
			Iterator<Point> pointsIterator = points.iterator();
			
			while (pointsIterator.hasNext()) {
				Point currentPoint = pointsIterator.next();
				
				for (String clusterName : clusterNames) {
					dist = currentPoint.calculateDistance(clusters.get(clusterName).getCentroid());
					if (dist < minDist) {
						minDist = dist;
						name = clusterName;
					}
				}
				
				if (!name.equals(peer.getPeerName())) { // If the point did not stay in my cluster, send a message to the correct peer
					pointsIterator.remove();
					ClusterMessage m = new ClusterMessage(POINT, new Cluster(-1, -1, -1, currentPoint, new Point[0]));
					peer.send(name, m);
					send++; // TODO REMOVE
				}
			}
//			System.out.println(":assignpoints: " + peerName + "-" + round + "| assigned " + send + " points"); //TODO REMOVE
		}
		
		/**
		 * 
		 * @param peer
		 * @throws IOException
		 */
		private void updateCluster(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException {
//			System.out.println(":updatecluster: " + peerName + "-" + round); //TODO REMOVE
			recalculateMean(peer); // set Centroid
			setOutliers(peer); // set Outliers
			broadcastClusterInfo(peer); // Send your cluster info around
		}
		
		/**
		 * Recalculate the centroid of this cluster.
		 * @param peer: a BSPPeer object containing all the information about 
		 * 	this BSPPeer task.
		 * @throws IOException from the broadcastClusterInfo() method
		 */
		private void recalculateMean(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException {
//			System.out.println(":recalculatemean: " + peerName + "-" + round); //TODO REMOVE
			int size = 0;
			Point newCentroid = new Point();
			for (Point point : points) {
				if (size == 0) {
					newCentroid.setCoordinates(point.getCoordinates());
					me.setDimensions(newCentroid.getCoordinates().length); // set the number of Dimensions
				} else {
					newCentroid.add(point);
				}
				size++;
			}
			newCentroid.divide(size);
			me.setSize(size); // set the size
			me.setCentroid(newCentroid);
		}
		
		/**
		 * Set the k - 1 outliers of this cluster, by 
		 *  calculating which points have the largest
		 *  distance to the center 
		 * @param peer: a BSPPeer object containing all the information about 
		 * 	this BSPPeer task.
		 * @throws IOException from the broadcastOutliers() method.
		 */
		private void setOutliers(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException {
//			System.out.println(":setoutliers: " + peerName + "-" + round); //TODO REMOVE
			Point centroid = me.getCentroid();
			Point[] outliers = new Point[kmeans - 1];
			Point[] newOutliers = new Point[outliers.length];
			for (Point point : points) {
				boolean gotPosition = false;
				double dist = point.calculateDistance(centroid);
				for (int k = 0; k < outliers.length; k++) {
					if (!gotPosition) {
						newOutliers[k] = outliers[k];
						if (newOutliers[k] == null || outliers[k].calculateDistance(centroid) < dist) {
							newOutliers[k] = new Point(point.getCoordinates(), new String[0]);
							gotPosition = true;
						}
					} else {
						newOutliers[k] = outliers[k - 1];
					}
				}
				outliers = Arrays.copyOf(newOutliers, newOutliers.length);
			}
			for(int i = 0; i < newOutliers.length; i++) { //TODO REMOVE
				if(newOutliers[i] != null){ //TODO REMOVE
					System.out.println(":setoutliers: " + peerName + "-" + round + "| newOutlier: " + newOutliers[i].toString()); //TODO REMOVE
				} else { //TODO REMOVE
					System.out.println(":setoutliers: " + peerName + "-" + round + "| newOutlier: null"); //TODO REMOVE
				} //TODO REMOVE
			} //TODO REMOVE
			me.setOutliers(newOutliers);
		}
		
		/**
		 * Broadcast the newly calculated centroid of this cluster
		 *  to the other clusters.
		 * @param peer: a BSPPeer object containing all the information about 
		 * 	this BSPPeer task.
		 * @throws IOException from the send() method
		 */
		private void broadcastClusterInfo(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException {
//			System.out.println(":broadcast: " + peerName + "-" + round); //TODO REMOVE
			String[] clusterNames = peer.getAllPeerNames();
			for (String clusterName : clusterNames) {
				ClusterMessage m = new ClusterMessage(peer.getPeerName(), new Cluster(me));
				peer.send(clusterName, m);
			}
		}
		
		/**
		 * Check whether the algorithm has converged, 
		 *  compared to the previous cluster centroid and
		 *  the current cluster centroids.
		 * @param newClusters: A HashMap object containing
		 *  the new cluster_name/mean combinations.
		 * @return true in case the algorithm, false
		 *  in case it did not.
		 */
		private boolean checkConvergence(HashMap<String, Cluster> newClusters) {
//			System.out.println(":checkconvergence: " + peerName + "-" + round); //TODO REMOVE
			if (clusters.size() != newClusters.size()) { // if the sizes do not equal, 'clusters' was smaller than kmeans the previous round; hence no convergence
//				System.out.println(":checkconvergence: " + peerName + "-" + round + "| inequal sizes | " + clusters.size() + " != " + newClusters.size()); //TODO REMOVE
				return false;
			}
			for (Entry<String, Cluster> entry : clusters.entrySet()) {
				Cluster currentCluster = entry.getValue();
				Cluster newCluster = newClusters.get(entry.getKey());
				if (currentCluster.getSize() == 0 || newCluster.getSize() == 0) { // Found a cluster with no points, hence no mean to compare\
//					System.out.println(":checkconvergence: " + peerName + "-" + round + "| empty cluster | " + currentCluster.getSize() + " == 0 || " + newCluster.getSize() + " == 0"); //TODO REMOVE
					return false; // Thus cannot check for convergence
				}
			}
			
			double cmp = 0.0;
			for (Entry<String, Cluster> entry : clusters.entrySet()) {
				Cluster currentCluster = entry.getValue();
				Cluster newCluster = newClusters.get(entry.getKey());
//				System.out.println(":checkconvergence: " + peerName + "-" + round + "| compare | \n" + currentCluster.getCentroid().toString() + "\n == \n" + newCluster.getCentroid().toString()); //TODO REMOVE
				cmp += currentCluster.getCentroid().compareTo(newCluster.getCentroid(), CONVERGENCE_POINT);
				if (cmp != 0) {
					return false;
				}
			}
			return true;
		}
		
		/**
		 * Set the newly found clusters as
		 *  the current clusters
		 * @param newClusters: A HashMap containing the new clusters
		 */
		private void setNewClusters(HashMap<String, Cluster> newClusters) {
//			System.out.println(":setnewclusters: " + peerName + "-" + round); //TODO REMOVE
			int emptyClusters = 0;
			for (Entry<String, Cluster> entry : newClusters.entrySet()) {
				String name = entry.getKey(); // Retrieving/removing the key-value pair
				Cluster cluster = new Cluster(entry.getValue()); // TODO CHANGE?
				if (cluster.getSize() <= 0) {
					System.out.println(":setnewclusters: " + peerName + "-" + round + " found empty cluster: " + cluster.toString()); //TODO REMOVE
					emptyClusters++;
				}
//				System.out.println(":setnewclusters: " + peerName + "-" + round + " found cluster: " + cluster.toString()); //TODO REMOVE
				
				clusters.remove(name); // Setting the new key-value pair
				clusters.put(name, cluster);
			}
			
			if (emptyClusters > 0) {
				selectNewClusters(emptyClusters);
			}
		}
		
		private void selectNewClusters(int numEmptyClusters) {
			System.out.println(":selectnewclusters: " + peerName + "-" + round + "| found " + numEmptyClusters); //TODO REMOVE
			int i = 0;
			String[] emptyClusterNames = new String[numEmptyClusters];
			Cluster largestCluster = new Cluster();
			for (Entry<String, Cluster> entry : clusters.entrySet()) {
				// Find the indexes of the empty clusters
				if (entry.getValue().getSize() == 0) {
					emptyClusterNames[i] = entry.getKey();
					i++;
				} // Select the largest cluster to retrieve the outliers from to become new clusters 
				else if (entry.getValue().getSize() > largestCluster.getSize()) {
					largestCluster = new Cluster(entry.getValue());
				}
			}
			
			System.out.println(":selectnewclusters: " + peerName + "-" + round + "| select from " + largestCluster.toString()); //TODO REMOVE
			
			// Select the new cluster centroids
			Point[] outliers = largestCluster.getOutliers();
			for (int j = 0; j < numEmptyClusters; j++) {
				Cluster newCluster = new Cluster(clusters.get(emptyClusterNames[j]));
				newCluster.setCluster(newCluster.getIndex(), 1, largestCluster.getDimensions(), outliers[j], new Point[0]);
				System.out.println(":selectnewclusters: " + peerName + "-" + round + "| have set " + newCluster.toString()); //TODO REMOVE
				
				clusters.remove(emptyClusterNames[j]); // Setting the new key-value pair
				clusters.put(emptyClusterNames[j], newCluster);
				
				if(emptyClusterNames[j].equals(peerName)){ // If my cluster was empty and was replaced by this one..
					me.setSize(1);
					me.setCentroid(new Point(outliers[j]));
				}
			}
		}
		
		@Override
		public void cleanup(BSPPeer<LongWritable, Text, IntWritable, Text, ClusterMessage> peer) throws IOException { // Close
			for (Point point : points) { // Write the mean - Point pairs out to a file
				peer.write(new IntWritable(me.getIndex()), new Text(point.toString()));
			}
			points.clear(); // Empty memory
		}
	}
	
	/**
	 * Create the job.
	 * @param args: String array of arguments
	 * @param conf: a HamaConfiguration Object for the BSP job
	 * @return a finalized BSPJob Object for this BSP job
	 * @throws IOException for creating the BSP job Object
	 */
	public static BSPJob createJob(HamaConfiguration conf, Path inputPath, Path outputPath, int kmeans) throws IOException {
		BSPJob job = new BSPJob(conf, KMeans.class); // Main settings
		job.setJobName("KMeans Clustering");
		job.setBspClass(KMeansBSP.class);
		job.setNumBspTask(kmeans);
		job.setInputPath(inputPath); // Input settings
		job.setInputFormat(TextInputFormat.class);
		job.setInputKeyClass(LongWritable.class);
		job.setInputValueClass(Text.class);
		job.setOutputPath(outputPath); // Output settings
		job.setOutputFormat(TextOutputFormat.class);
		job.setOutputKeyClass(Point.class);
		job.setOutputValueClass(Text.class);
		
		return job;
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
	
	@Override
	/**
	 * Runs the main program
	 * 
	 * @param args: String array of arguments given at start 
	 * @return -1 in case of error | 0 in case of success
	 * @throws Exception from the createJob() and the waitForCompletion() methods
	 */
	public int run(String[] args) throws Exception {
		int kmeans = 0, iterations = 0;
		Path inputPath = null, outputPath = null;
		HamaConfiguration conf = new HamaConfiguration(getConf());
		
		// Set arguments
		if (args.length < 2) {
			System.err.println("Error: too few parameters given");
			return printUsage();
		}
		inputPath = new Path(args[0]);
		outputPath = new Path(args[1]);
		try {
			kmeans = Integer.parseInt(args[2]);
			conf.setInt("kmeans", kmeans);
			iterations = Integer.parseInt(args[3]);
			conf.setInt("iterations", iterations);
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
		
		// Create and start a job
		BSPJob job = createJob(conf, inputPath, outputPath, kmeans);
		long startTime = System.currentTimeMillis();
		if (job.waitForCompletion(true)) {
			System.out.println("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");
		}
		return 0;
	}
	
	public static void main(String[] args) throws Exception {
		int result = ToolRunner.run(new Configuration(), new KMeans(), args);
		System.exit(result);
	}
}
