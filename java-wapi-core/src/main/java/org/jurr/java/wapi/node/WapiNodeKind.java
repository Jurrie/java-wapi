package org.jurr.java.wapi.node;

/** The type of a node, in bits 4 through 7 of its definition flags (PDF Table 3, Node Flags). */
public enum WapiNodeKind
{
	/** A type code that PDF Table 3 does not list. */
	UNKNOWN(-1),
	/** 0: node. */
	NODE(0),
	/** 1: lin float. */
	LIN_FLOAT(1),
	/** 2: log float. */
	LOG_FLOAT(2),
	/** 3: fader level. */
	FADER_LEVEL(3),
	/** 4: integer. */
	INTEGER(4),
	/** 5: string enum. */
	STRING_ENUM(5),
	/** 6: float enum. */
	FLOAT_ENUM(6),
	/** 7: string. */
	STRING(7);

	private final int code;

	WapiNodeKind(final int code)
	{
		this.code = code;
	}

	/** {@return node-kind code stored in bits 4 through 7 of the node flags} */
	public int getCode()
	{
		return code;
	}

	/**
	 * Resolves the node kind stored in bits 4 through 7 of node flags (PDF Table 3, Node Flags;
	 * confirmed with definitions captured from a console, e.g. {@code 0x0070} for a string).
	 *
	 * @param flags raw node-definition flags
	 * @return matching kind, or {@link #UNKNOWN} for an unrecognized nibble
	 */
	public static WapiNodeKind fromFlags(final int flags)
	{
		final var type = (flags >>> 4) & 0x0F;
		for (final var kind : values())
		{
			if (kind.code == type)
			{
				return kind;
			}
		}
		return UNKNOWN;
	}
}
