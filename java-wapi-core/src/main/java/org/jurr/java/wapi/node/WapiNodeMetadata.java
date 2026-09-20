package org.jurr.java.wapi.node;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional range, length, and enumeration metadata for a node definition. Each value is {@code null}
 * when the node kind has no such field.
 *
 * @param minFloat lowest value of a float node
 * @param maxFloat highest value of a float node
 * @param steps number of steps of a float node
 * @param minInt lowest value of an integer node
 * @param maxInt highest value of an integer node
 * @param maxStringLength longest value of a string node
 * @param enumItems items of an enum node; stored as an unmodifiable copy, empty for other kinds
 */
public record WapiNodeMetadata(Float minFloat, Float maxFloat, Long steps, Integer minInt, Integer maxInt, Integer maxStringLength,
		List<WapiEnumItem> enumItems)
{
	/**
	 * Copies the enum items.
	 *
	 * @param minFloat lowest value of a float node
	 * @param maxFloat highest value of a float node
	 * @param steps number of steps of a float node
	 * @param minInt lowest value of an integer node
	 * @param maxInt highest value of an integer node
	 * @param maxStringLength longest value of a string node
	 * @param enumItems items of an enum node; stored as an unmodifiable copy, empty for other kinds
	 * @throws NullPointerException if {@code enumItems} is {@code null}
	 */
	public WapiNodeMetadata
	{
		enumItems = List.copyOf(enumItems);
	}

	/** {@return the enum item values as text, in order} */
	public List<String> enumValues()
	{
		final var values = new ArrayList<String>();
		for (final var item : enumItems)
		{
			values.add(item.value().toString());
		}
		return List.copyOf(values);
	}
}
