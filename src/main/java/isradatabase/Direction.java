package isradatabase;

/**
 * Vertex' edges direction, IN OUT or BOTH 3 directions.
 */
public enum Direction {
	OUT, IN, BOTH;

	/**
	 * Convert from ISRA direction to backend direction required by backend database.
	 * @param direction
	 * @return
	 */
	public com.tinkerpop.blueprints.Direction backendConvert() {
		if (this.equals(Direction.OUT))
			return com.tinkerpop.blueprints.Direction.OUT;
		else if (this.equals(Direction.IN))
			return com.tinkerpop.blueprints.Direction.IN;
		else if (this.equals(Direction.BOTH))
			return com.tinkerpop.blueprints.Direction.BOTH;
		throw new IllegalStateException("Unknown direction type: " + this);
	}
}