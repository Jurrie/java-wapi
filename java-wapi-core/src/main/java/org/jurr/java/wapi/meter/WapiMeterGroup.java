package org.jurr.java.wapi.meter;

/** Meter collection groups of a Channel 3 meter request (PDF Table 4, Meter Request Tokens). */
public enum WapiMeterGroup
{
	/** Channel 1 to 40 (token {@code 0xa0}). */
	CHANNEL(0xA0, 40),
	/** Aux 1 to 8 (token {@code 0xa1}). */
	AUX(0xA1, 8),
	/** Bus 1 to 16 (token {@code 0xa2}). */
	BUS(0xA2, 16),
	/** Main 1 to 4 (token {@code 0xa3}). */
	MAIN(0xA3, 4),
	/** Matrix 1 to 8 (token {@code 0xa4}). */
	MATRIX(0xA4, 8),
	/** DCA 1 to 8 (token {@code 0xa5}). */
	DCA(0xA5, 8),
	/** FX processor 1 to 16 (token {@code 0xa6}). */
	FX(0xA6, 16),
	/** Source (input) device 1 to 16 (token {@code 0xa7}). */
	SOURCE(0xA7, 16),
	/** Output device 1 to 11 (token {@code 0xa8}). */
	OUTPUT(0xA8, 11),
	/** Monitor, without index (token {@code 0xa9}). */
	MONITOR(0xA9, 0),
	/** RTA, without index (token {@code 0xaa}). */
	RTA(0xAA, 0),
	/** Channel V2 1 to 40 (token {@code 0xab}). */
	CHANNEL_V2(0xAB, 40),
	/** Aux V2 1 to 8 (token {@code 0xac}). */
	AUX_V2(0xAC, 8),
	/** Bus V2 1 to 16 (token {@code 0xad}). */
	BUS_V2(0xAD, 16),
	/** Main V2 1 to 4 (token {@code 0xae}). */
	MAIN_V2(0xAE, 4),
	/** Matrix V2 1 to 8 (token {@code 0xaf}). */
	MATRIX_V2(0xAF, 8);

	private final int token;
	private final int maxIndex;

	WapiMeterGroup(final int token, final int maxIndex)
	{
		this.token = token;
		this.maxIndex = maxIndex;
	}

	/** {@return meter request token byte for this group} */
	public int getToken()
	{
		return token;
	}

	/** {@return highest one-based index of this group, or 0 when the group takes no index} */
	public int getMaxIndex()
	{
		return maxIndex;
	}

	/** {@return whether this group is followed by one or more indexes in a meter request} */
	public boolean hasIndex()
	{
		return maxIndex > 0;
	}
}
