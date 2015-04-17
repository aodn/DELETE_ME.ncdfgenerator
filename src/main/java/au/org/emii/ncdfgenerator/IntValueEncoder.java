
package au.org.emii.ncdfgenerator;

import ucar.ma2.Array;
import ucar.ma2.DataType;

import java.util.Map;

public class IntValueEncoder implements IValueEncoder
{
	// Int is 32bit in Netcdf

	int fill;

	IntValueEncoder()
	{
		this.fill = 1234;
	}

	public DataType targetType()
	{
		return DataType.INT;
	}

	public void prepare( Map<String, String> attributes )
	{
		fill = Integer.valueOf( attributes.get( "_FillValue" )).intValue();
	}

	public void encode( Array array, int ima, Object value ) throws NcdfGeneratorException
	{
		if( value == null) {
			array.setInt( ima, fill );
		}
		else if( value instanceof Integer ) {
			array.setInt( ima, (Integer) value);
		}
		else if( value instanceof Long ) {
			array.setInt( ima, (int)(long)(Long) value);
		}
		else {
			throw new NcdfGeneratorException( "Failed to coerce type '" + value.getClass() + "' to float" );
		}
	}
}

