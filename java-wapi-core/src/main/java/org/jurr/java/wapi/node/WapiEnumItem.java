package org.jurr.java.wapi.node;

import java.util.Objects;

import org.jurr.java.wapi.WapiValue;

/**
 * One item of a string or float enum node definition: the value that is read and set, and an
 * optional display label (the PDF's "long item").
 *
 * @param value non-null item value: a string for a string enum, a float for a float enum, for example
 *            {@code A+B} or {@code 1.1}
 * @param longName display label, for example {@code INPUT} or {@code 1.1:1}; empty when the console
 *            sends none; {@code null} is normalized to an empty string
 */
public record WapiEnumItem(WapiValue value, String longName)
{
	/**
	 * Checks the value; a {@code null} long name becomes empty.
	 *
	 * @param value non-null item value: a string for a string enum, a float for a float enum, for example
	 *            {@code A+B} or {@code 1.1}
	 * @param longName display label, for example {@code INPUT} or {@code 1.1:1}; empty when the console
	 *            sends none; {@code null} is normalized to an empty string
	 * @throws NullPointerException if {@code value} is {@code null}
	 */
	public WapiEnumItem
	{
		Objects.requireNonNull(value, "value");
		longName = longName == null ? "" : longName;
	}

	/** {@return the value as text, followed by the label in parentheses when there is one} */
	@Override
	public String toString()
	{
		return longName.isEmpty() ? value.toString() : value + " (" + longName + ")";
	}
}
