package isradatabase;

import com.orientechnologies.orient.core.metadata.schema.OType;

/**
 * Graph component (Vertex & Edge) underlying property type.
 */
public enum PropertyType {
	INTEGER, DOUBLE, FLOAT, LONG, BOOLEAN, BINARY, STRING;

	/**
	 * Convert from ISRA direction to backend direction required by backend database.
	 * @param direction
	 * @return
	 */
	public OType backendConvert() {
		if (this.equals(PropertyType.INTEGER))
			return OType.INTEGER;
		else if (this.equals(PropertyType.DOUBLE))
			return OType.DOUBLE;
		else if (this.equals(PropertyType.FLOAT))
			return OType.FLOAT;
		else if (this.equals(PropertyType.LONG))
			return OType.LONG;
		else if (this.equals(PropertyType.BOOLEAN))
			return OType.BOOLEAN;
		else if (this.equals(PropertyType.BINARY))
			return OType.BINARY;
		else if (this.equals(PropertyType.STRING))
			return OType.STRING;
		throw new IllegalStateException("Unknown property type: " + this);
	}
}