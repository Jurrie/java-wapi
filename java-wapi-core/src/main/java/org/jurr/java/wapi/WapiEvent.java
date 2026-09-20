package org.jurr.java.wapi;

import java.util.Objects;

/**
 * A decoded unsolicited token update, equivalent to one C wTV record.
 *
 * @param token event token; must not be {@code null}
 * @param type decoded protocol type; must not be {@code null}
 * @param value decoded token value; must not be {@code null}
 */
public record WapiEvent(WapiToken token, WapiType type, WapiValue value)
{
	/**
	 * Checks that every component is set.
	 *
	 * @param token event token; must not be {@code null}
	 * @param type decoded protocol type; must not be {@code null}
	 * @param value decoded token value; must not be {@code null}
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public WapiEvent
	{
		Objects.requireNonNull(token, "token");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(value, "value");
	}
}
