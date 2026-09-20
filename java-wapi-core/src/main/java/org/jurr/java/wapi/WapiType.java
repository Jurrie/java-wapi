package org.jurr.java.wapi;

/** The type of a value, as {@code wtype} in {@code wapi.h}; the descriptions are those of {@code wapi.h}. */
public enum WapiType
{
	/** Unknown type. */
	UNKN(-1),
	/** Node type (unsigned int). */
	NODE(0),
	/** Int type. */
	I32(1),
	/** Float type. */
	F32(2),
	/** String type. */
	S32(3),
	/** An unsigned int type to accept all of the above. */
	V32(4);

	private final int code;

	WapiType(final int code)
	{
		this.code = code;
	}

	/** {@return numeric type code used by the high-level message format} */
	public int getCode()
	{
		return code;
	}

	/**
	 * Resolves a numeric type code.
	 *
	 * @param code numeric type code
	 * @return matching type, or {@link #UNKN} if no type uses the code
	 */
	public static WapiType fromCode(final int code)
	{
		for (final var type : values())
		{
			if (type.code == code)
			{
				return type;
			}
		}
		return UNKN;
	}
}
