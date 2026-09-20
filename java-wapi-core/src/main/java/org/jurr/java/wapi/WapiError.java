package org.jurr.java.wapi;

/**
 * The error codes of the C wapi library ({@code werror} in {@code wapi.h}), carried by a
 * {@link WapiException}. The descriptions are those of {@code wapi.h}.
 */
public enum WapiError
{
	/** Socket option error (wapi.h: "socket option keepalive error"); here when {@code TCP_NODELAY} cannot be set. */
	WSNDL_NTW_ERROR(-19),
	/** Socket option keepalive error. */
	WSKAL_NTW_ERROR(-18),
	/** Network socket connect error. */
	WSCON_NTW_ERROR(-17),
	/** Network socket create error. */
	WSOCK_NTW_ERROR(-16),
	// WWSA_NTW_ERROR(-15), // Not used in this implementation, but the original WAPI has it.
	// WTYPE(-14), // Not used in this implementation, but the original WAPI has it.
	// WHASH(-13), // Not used in this implementation, but the original WAPI has it.
	// WNODEPARAM(-12), // Not used in this implementation, but the original WAPI has it.
	// WNODEVALUE(-11), // Not used in this implementation, but the original WAPI has it.
	// WRECV_BUF_ERROR(-10), // Not used in this implementation, but the original WAPI has it.
	// WMEMORY(-9), // Not used in this implementation, but the original WAPI has it.
	/** Bind UDP error. */
	WBIND_UDP_ERROR(-8),
	// WSEND_NTW_ERROR(-7), // Not used in this implementation, but the original WAPI has it.
	/** General UDP send error. */
	WSEND_UDP_ERROR(-6),
	/** General TCP send error. */
	WSEND_TCP_ERROR(-5),
	// WSEND_ERROR(-4), // Not used in this implementation, but the original WAPI has it.
	/** Receive error. */
	WRECV_ERROR(-3),
	/** Invalid token (wrong type or unknown). */
	WTOKEN(-2),
	/** Invalid node (wrong type or unknown). */
	WNODE(-1),
	/** Not good, not bad; also the code of a {@link WapiException} without a specific error, and of an unknown numeric code. */
	WZERO(0);
	// WSUCCESS(1); // Not used in this implementation, but the original WAPI has it.

	private final int code;

	WapiError(final int code)
	{
		this.code = code;
	}

	/** {@return numeric WAPI error code} */
	public int getCode()
	{
		return code;
	}

	/**
	 * Resolves a numeric WAPI error code.
	 *
	 * @param code numeric error code
	 * @return matching error, or {@link #WZERO} when unknown
	 */
	public static WapiError fromCode(final int code)
	{
		for (final var error : values())
		{
			if (error.code == code)
			{
				return error;
			}
		}
		return WZERO;
	}
}
