package org.jurr.java.wapi;

/** Unchecked exception for WAPI protocol, transport, and validation failures. */
public class WapiException extends RuntimeException
{
	private static final long serialVersionUID = 1L;
	/** The WAPI error code; never {@code null}. */
	private final WapiError error;

	/**
	 * Creates an exception with the default {@link WapiError#WZERO} error code.
	 *
	 * @param message detail describing the failure
	 */
	public WapiException(final String message)
	{
		this(message, WapiError.WZERO, null);
	}

	/**
	 * Creates an exception with the default {@link WapiError#WZERO} error code and an underlying cause.
	 *
	 * @param message detail describing the failure
	 * @param cause underlying failure, or {@code null} when absent
	 */
	public WapiException(final String message, final Throwable cause)
	{
		this(message, WapiError.WZERO, cause);
	}

	/**
	 * Creates an exception with the supplied error code and no cause.
	 *
	 * @param message detail describing the failure
	 * @param error WAPI error code; {@code null} is normalized to {@link WapiError#WZERO}
	 */
	public WapiException(final String message, final WapiError error)
	{
		this(message, error, null);
	}

	/**
	 * Creates an exception with a message, WAPI error code, and optional cause.
	 *
	 * @param message detail describing the failure
	 * @param error WAPI error code; {@code null} is normalized to {@link WapiError#WZERO}
	 * @param cause underlying failure, or {@code null} when absent
	 */
	public WapiException(final String message, final WapiError error, final Throwable cause)
	{
		super(message, cause);
		this.error = error == null ? WapiError.WZERO : error;
	}

	/** {@return the non-null WAPI error code} */
	public WapiError getError()
	{
		return error;
	}

	/** {@return the numeric code associated with {@link #getError()}} */
	public int getCode()
	{
		return error.getCode();
	}
}
