import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;

/**
 * Basic Text-Pair class implementation for Hadoop
 * 
 * @from Hadoop: The Definitive Guide
 * @date 27-03-2013
 */
public class TextPair implements WritableComparable<TextPair> {
	
	private Text first;
	private Text second;
	
	public TextPair() {
		this.first = new Text();
		this.second = new Text();
	}
	
	public TextPair(Text first, Text second) {
		this.first = first;
		this.second = second;
	}
	
	public TextPair(String first, String second) {
		this(new Text(first), new Text(second));
	}
	
	public void set(TextPair pair){
		this.first = pair.getFirst();
		this.second = pair.getSecond();
	}
	
	public void set(Text first, Text second) {
		this.first = first;
		this.second = second;
	}
	
	public void set(String first, String second) {
		this.first.set(first);
		this.second.set(second);
	}
	
	//get-set first Text variable
	public Text getFirst() { return first; }
	public void setFirst(String first){ this.first = new Text(first); }
	
	//get-set second Text variable
	public Text getSecond() { return second; }
	public void setSecond(String second){ this.second = new Text(second); }
	
	@Override
	public void write(DataOutput out) throws IOException {
		first.write(out);
		second.write(out);
	}
	@Override
	public void readFields(DataInput in) throws IOException {
		first.readFields(in);
		second.readFields(in);
	}
	
	@Override
	public int hashCode() {
		int result = first != null ? first.hashCode() : 0;
		result = 163 * result + (second != null ? second.hashCode() : 0);
		return result;
	}
	
	@Override
	public boolean equals(Object o) {
		if(this == o) 
			return true;
		if(o == null || getClass() != o.getClass())
			return false;
		TextPair tp = (TextPair)o;
		if(second != null ? !second.equals(tp.getSecond()) : tp.getSecond() != null)
			return false;
		if(first != null ? !first.equals(tp.getFirst()) : tp.getFirst() != null)
			return false;
		return true;
	}
	
	@Override
	public String toString() {
		return first + "\t" + second;
	}

	@Override
	public int compareTo(TextPair tp) {
		int cmp = this.first.compareTo(tp.first);
		if (cmp != 0) {
			return cmp;
		}
		return this.second.compareTo(tp.second);
	}
}