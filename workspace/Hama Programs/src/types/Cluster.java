package types;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

import org.apache.hadoop.io.WritableComparable;

/**
 * A Writable object to hold a cluster for
 *  the k-means algorithm
 * @author stevenb
 * @date 14-08-2013
 */
public class Cluster implements WritableComparable<Cluster> {
	
	private int index, size, dimensions;
	private Point centroid;
	private Point[] outliers;
	
	public Cluster(int index, int size, int dimensions, Point centroid, Point[] outliers) {
		this.index = index;
		this.size = size;
		this.dimensions = dimensions;
		this.centroid = centroid;
		this.outliers = outliers;
	}
	
	public Cluster(Cluster cluster){
		this.index = cluster.getIndex();
		this.size = cluster.getSize();
		this.dimensions = cluster.getDimensions();
		this.centroid = cluster.getCentroid();
		this.outliers = cluster.getOutliers();
	}
	
	public Cluster() {
		this(-1, 0, 0, null, null);
	}
	
	public int getIndex() {
		return index;
	}
	
	public void setIndex(int index) {
		this.index = index;
	}
	
	public int getSize() {
		return size;
	}
	
	public void setSize(int size) {
		this.size = size;
	}
	
	public int getDimensions() {
		return dimensions;
	}
	
	public void setDimensions(int dimensions) {
		this.dimensions = dimensions;
	}
	
	public Point getCentroid() {
		return centroid;
	}
	
	public void setCentroid(Point centroid) {
		this.centroid = centroid;
	}
	
	public Point[] getOutliers() {
		return outliers;
	}
	
	public void setOutliers(Point[] outliers) {
		this.outliers = Arrays.copyOf(outliers, outliers.length);
//		this.outliers = outliers;
	}
	
	public void setCluster(int index, int size, int dimensions, Point centroid, Point[] outliers) {
		this.index = index;
		this.size = size;
		this.dimensions = dimensions;
		this.centroid = centroid;
		this.outliers = outliers;
	}
	
	public boolean isEmpty() {
		if (index == -1) {
			return true;
		}
		return false;
	}
	
	public void parseCluster(String clusterInfo, int kmeans) {
		String[] clusterInfoValues = clusterInfo.toString().replaceAll("\\s+", " ").split("\\s+");
		int start = 0, end = clusterInfoValues.length;
		outliers = new Point[kmeans - 1];
		
		try { // Parse index and size
			index = (int) Double.parseDouble(clusterInfoValues[0]); // Use parseDouble() to make '0' parsing valid
			size = (int) Double.parseDouble(clusterInfoValues[1]);
			dimensions = (int) Double.parseDouble(clusterInfoValues[2]);
		} catch (NumberFormatException e) {
			System.err.println("Error: expected an Integer instead of " + clusterInfoValues[0] + " " + clusterInfoValues[1]);
		}
		
		if (isEmpty()) { // Empty cluster encountered, cannot parse mean and outliers
			return;
		}
		
		// Parse cluster centroid
		start += 3;
		double[] coordinates = new double[dimensions];
		for (int i = start; i < start + dimensions; i++) {
			try {
				coordinates[i - 3] = Double.parseDouble(clusterInfoValues[i]);
			} catch (NumberFormatException e) {
				System.err.println("Error: expected a Double instead of " + clusterInfoValues[i]);
			}
		}
		centroid = new Point(coordinates, new String[0]);
		
		// Parse clusters k - 1 outliers
		for (int i = 0; i < kmeans - 1; i++) {
			double[] outlierCoordinates = new double[dimensions];
			start += dimensions;
			if (start == end) {
				break;
			}
			for (int j = start; j < start + dimensions; j++) {
				try {
					outlierCoordinates[j - start] = Double.parseDouble(clusterInfoValues[j]);
				} catch (NumberFormatException e) {
					System.err.println("Error: expected a Double instead of " + clusterInfoValues[i]);
				}
			}
			outliers[i] = new Point(outlierCoordinates, new String[0]);	
		}
	}
	
	@Override
	public String toString() {
		StringBuilder b = new StringBuilder();
		
		b.append(index);
		b.append(" ");
		b.append(size);
		b.append(" ");
		b.append(dimensions);
		
		if (centroid != null) {
			b.append("[");
			b.append(centroid.toString());
			b.append(" ] { ");
		}
		
		if (outliers != null) {
			for (int i = 0; i < outliers.length; i++) {
				if (outliers[i] != null) {
					b.append("[");
					b.append(outliers[i].toString());
					b.append(" ] ");
				} else {
					b.append("[ ] ");
				}
			}
		}
		b.append(" }");
		
		return b.toString();
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		index = in.readInt();
		size = in.readInt();
		dimensions = in.readInt();
		centroid.readFields(in);
		int numberOfOutliers = in.readInt();
		outliers = new Point[numberOfOutliers];
		for(int i = 0; i < numberOfOutliers; i++){
			outliers[i].readFields(in);
		}
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeInt(index);
		out.writeInt(size);
		out.writeInt(dimensions);
		centroid.write(out);
		int numberOfOutliers = outliers.length;
		out.writeInt(numberOfOutliers);
		for(int i = 0; i < numberOfOutliers; i++){
			outliers[i].write(out);
		}
	}

	@Override
	public int compareTo(Cluster other) {
		int cmp = index - other.getIndex();
		if(cmp != 0){
			return cmp;
		}
		cmp = size - other.getSize();
		if(cmp != 0){
			return cmp;
		}
		cmp = dimensions - other.getDimensions();
		if(cmp != 0){
			return cmp;
		}

		cmp = centroid.compareTo(other.getCentroid());
		if(cmp != 0){
			return cmp;
		}
		
		Point[] otherOutliers = other.getOutliers();
		for(int i = 0; i < outliers.length; i++){
			try{
				cmp = outliers[i].compareTo(otherOutliers[i]);
				if(cmp != 0){
					return cmp;
				}		
			} catch (Exception e){
				return -1;
			}
		}
		
		return 0;
	}
}