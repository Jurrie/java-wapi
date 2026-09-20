package org.jurr.java.wapi;

/** Immutable tagged value used for WING token reads, writes, and events. */
public final class WapiValue
{
	private final WapiType type;
	private final Integer intValue;
	private final Float floatValue;
	private final String stringValue;
	private final Long longValue;

	private WapiValue(final WapiType type, final Integer intValue, final Float floatValue, final String stringValue, final Long longValue)
	{
		this.type = type;
		this.intValue = intValue;
		this.floatValue = floatValue;
		this.stringValue = stringValue;
		this.longValue = longValue;
	}

	/**
	 * {@return an {@code I32} value}
	 *
	 * @param value signed 32-bit value to store
	 */
	public static WapiValue ofInt(final int value)
	{
		return new WapiValue(WapiType.I32, value, null, null, null);
	}

	/**
	 * {@return an {@code F32} value}
	 *
	 * @param value 32-bit floating-point value to store
	 */
	public static WapiValue ofFloat(final float value)
	{
		return new WapiValue(WapiType.F32, null, value, null, null);
	}

	/**
	 * {@return an {@code S32} value}
	 *
	 * @param value string to store; may be {@code null}
	 */
	public static WapiValue ofString(final String value)
	{
		return new WapiValue(WapiType.S32, null, null, value, null);
	}

	/**
	 * {@return a {@code V32} value}
	 *
	 * @param value value stored in the unsigned 32-bit representation
	 */
	public static WapiValue ofUnsignedInt(final long value)
	{
		return new WapiValue(WapiType.V32, null, null, null, value);
	}

	/**
	 * {@return a {@code NODE} value}
	 *
	 * @param value node index/value to store
	 */
	public static WapiValue ofNode(final int value)
	{
		return new WapiValue(WapiType.NODE, value, null, null, null);
	}

	/** {@return the tag identifying which value accessor contains the value} */
	public WapiType getType()
	{
		return type;
	}

	/** {@return the integer payload for {@code I32} or {@code NODE}; otherwise {@code null}} */
	public Integer getIntValue()
	{
		return intValue;
	}

	/** {@return the floating-point payload for {@code F32}; otherwise {@code null}} */
	public Float getFloatValue()
	{
		return floatValue;
	}

	/** {@return the string payload for {@code S32}; otherwise {@code null}} */
	public String getStringValue()
	{
		return stringValue;
	}

	/** {@return the long payload for {@code V32}; otherwise {@code null}} */
	public Long getLongValue()
	{
		return longValue;
	}

	/** {@return the active payload formatted as text, or {@code "UNKN"} for an unknown tag} */
	@Override
	public String toString()
	{
		if (type == WapiType.I32)
		{
			return String.valueOf(intValue);
		}
		if (type == WapiType.F32)
		{
			return String.valueOf(floatValue);
		}
		if (type == WapiType.S32)
		{
			return stringValue;
		}
		if (type == WapiType.NODE)
		{
			return String.valueOf(intValue);
		}
		if (type == WapiType.V32)
		{
			return String.valueOf(longValue);
		}
		return "UNKN";
	}
}
