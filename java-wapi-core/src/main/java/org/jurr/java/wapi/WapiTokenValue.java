package org.jurr.java.wapi;

/**
 * A token/value pair corresponding to one element of a C wTV array.
 *
 * @param token token for the value; must not be {@code null}
 * @param value value associated with the token; must not be {@code null}
 */
public record WapiTokenValue(WapiToken token, WapiValue value)
{
	/**
	 * Checks that both components are set.
	 *
	 * @param token token for the value; must not be {@code null}
	 * @param value value associated with the token; must not be {@code null}
	 * @throws WapiException if either argument is {@code null}
	 */
	public WapiTokenValue
	{
		if (token == null || value == null)
		{
			throw new WapiException("Token and value are required");
		}
	}
}
