package types;
import org.apache.hadoop.io.ArrayWritable;
import org.apache.hadoop.io.Writable;

public class TextArrayWritable extends ArrayWritable {

	public TextArrayWritable(Class<? extends Writable> valueClass) {
		super(valueClass);
	}

	public String toString(){
		String[] strings = this.toStrings();
		String s = "";
		for(String string : strings)
			s = s + string + " ";
		return s;
	}
}