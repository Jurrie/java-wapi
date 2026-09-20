package org.jurr.java.wapi.node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenValue;
import org.jurr.java.wapi.WapiValue;

/** Parses and formats the documented dotted WAPI node assignment form: name=value,... */
public final class WapiNodeAssignmentCodec
{
	private WapiNodeAssignmentCodec()
	{
	}

	/**
	 * Parses comma-separated {@code name=value} assignments into typed token values.
	 * Names are resolved as in the PDF ("Node Data", {@code wSetNode}): a name is relative
	 * to the node of the previous assignment, a leading {@code /} starts at the root, and
	 * each leading {@code .} goes one level up. For example,
	 * {@code /ch.1.fdr=-1,mute=0,.2.fdr=0} sets {@code ch.1.fdr}, {@code ch.1.mute} and
	 * {@code ch.2.fdr}. The PDF only shows a single leading dot; several dots going up
	 * several levels matches the output of C {@code wGetNode}. Other slashes in a name are treated as dots. A value in
	 * {@code "..."} is a string without the quotes, as in the PDF ({@code /ch.1.name="space name"});
	 * it may contain commas and {@code =}. The PDF defines no escape for a quote inside a value. Exact
	 * {@code 0} and {@code 1} values become integers, other numeric values become floats,
	 * and remaining text becomes strings.
	 *
	 * @param assignments non-null, non-blank assignment text; each entry must contain a non-empty name and {@code =}
	 * @return mutable list of parsed token/value pairs in input order
	 * @throws WapiException if the input is missing or any assignment is malformed
	 */
	public static List<WapiTokenValue> parse(final String assignments)
	{
		if (assignments == null || assignments.trim().isEmpty())
		{
			throw new WapiException("Node assignments are required");
		}
		final var result = new ArrayList<WapiTokenValue>();
		final var currentNode = new ArrayList<String>();
		for (final var assignment : split(assignments))
		{
			final var parts = assignment.trim().split("=", 2);
			if (parts.length != 2 || parts[0].trim().isEmpty())
			{
				throw new WapiException("Invalid node assignment: " + assignment);
			}
			final var path = resolvePath(currentNode, parts[0].trim(), assignment);
			final var name = String.join(".", path);
			currentNode.clear();
			currentNode.addAll(path.subList(0, path.size() - 1));
			final var text = parts[1].trim();
			WapiValue value;
			if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
			{
				value = WapiValue.ofString(text.substring(1, text.length() - 1));
			}
			else if (text.equals("0") || text.equals("1"))
			{
				value = WapiValue.ofInt(Integer.parseInt(text));
			}
			else
			{
				try
				{
					value = WapiValue.ofFloat(Float.parseFloat(text));
				}
				catch (final NumberFormatException e)
				{
					value = WapiValue.ofString(text);
				}
			}
			final var type = value.getType();
			result.add(new WapiTokenValue(new WapiToken(name, type), value));
		}
		return result;
	}

	/**
	 * Formats node values the way C {@code wGetNode} does (PDF "wGetNode", verified against the
	 * output of libwapi.a for {@code ch.1}, {@code ch.1.flt}, {@code ch.25} and {@code ch.26}). The first name is relative to
	 * {@code node} with one leading {@code .}; every next name is relative to the node of the
	 * previous value, with one leading {@code .} for each level up. Floats get two decimals, empty
	 * strings are written as {@code ""}, other strings without quotes, even with a space in them, and every entry ends with a comma; the text ends with a line
	 * feed. For example, values of {@code ch.1.in.set.$mode}, {@code ch.1.in.conn.grp} and
	 * {@code ch.1.flt.lc} give {@code .in.set.$mode=M,.conn.grp=A,..flt.lc=1,}.
	 * <p>
	 * Deviation from C: a token without a known name is written as its hash ({@code 0x401E6CF6=...}),
	 * and the next name then starts at the root ({@code /ch.1.flt.lcs=...}).
	 *
	 * @param node non-null name of the requested node
	 * @param values non-null values in the order the console sent them
	 * @return the formatted values
	 */
	public static String format(final String node, final List<WapiTokenValue> values)
	{
		final var requested = List.of(node.split("\\."));
		final var result = new StringBuilder();
		List<String> previousNode = null;
		var first = true;
		for (final var tokenValue : values)
		{
			final var token = tokenValue.token();
			if (token.hasKnownName())
			{
				final var path = List.of(token.name().split("\\."));
				appendName(result, token.name(), path, first ? requested : null, previousNode);
				previousNode = path.subList(0, path.size() - 1);
			}
			else
			{
				result.append(String.format("0x%08X", token.hash()));
				previousNode = null;
			}
			first = false;
			result.append('=').append(formatValue(tokenValue.value())).append(',');
		}
		return result.append('\n').toString();
	}

	/**
	 * Appends a name: relative to the requested node for the first value, from the root when the
	 * previous value had no known name, and otherwise relative to the previous value's node.
	 *
	 * @param requested the requested node for the first value, otherwise {@code null}
	 * @param previousNode the node of the previous value, or {@code null} when its name was not known
	 */
	private static void appendName(final StringBuilder result, final String name, final List<String> path, final List<String> requested,
			final List<String> previousNode)
	{
		if (requested != null && startsWith(path, requested))
		{
			result.append('.').append(String.join(".", path.subList(requested.size(), path.size())));
			return;
		}
		if (previousNode == null)
		{
			result.append('/').append(name);
			return;
		}
		final var common = commonPrefixLength(previousNode, path.subList(0, path.size() - 1));
		result.append(".".repeat(previousNode.size() - common)).append(String.join(".", path.subList(common, path.size())));
	}

	/** {@return the number of leading elements that both lists have in common} */
	private static int commonPrefixLength(final List<String> first, final List<String> second)
	{
		var common = 0;
		while (common < first.size() && common < second.size() && first.get(common).equals(second.get(common)))
		{
			common++;
		}
		return common;
	}

	private static boolean startsWith(final List<String> path, final List<String> prefix)
	{
		return path.size() > prefix.size() && path.subList(0, prefix.size()).equals(prefix);
	}

	private static String formatValue(final WapiValue value)
	{
		return switch (value.getType())
		{
		case F32 -> String.format(Locale.ROOT, "%.2f", value.getFloatValue());
		case S32 -> value.getStringValue().isEmpty() ? "\"\"" : value.getStringValue();
		default -> value.toString();
		};
	}

	/** Splits at commas outside {@code "..."}. */
	private static List<String> split(final String assignments)
	{
		final var result = new ArrayList<String>();
		var quoted = false;
		var start = 0;
		for (var i = 0; i < assignments.length(); i++)
		{
			final char c = assignments.charAt(i);
			if (c == '"')
			{
				quoted = !quoted;
			}
			else if (c == ',' && !quoted)
			{
				result.add(assignments.substring(start, i));
				start = i + 1;
			}
		}
		if (quoted)
		{
			throw new WapiException("Invalid node assignments, unclosed quote: " + assignments);
		}
		result.add(assignments.substring(start));
		return result;
	}

	private static List<String> resolvePath(final List<String> currentNode, final String name, final String assignment)
	{
		final var path = new ArrayList<>(currentNode);
		var relative = name;
		if (relative.startsWith("/"))
		{
			path.clear();
			relative = relative.substring(1);
		}
		while (relative.startsWith("."))
		{
			if (path.isEmpty())
			{
				throw new WapiException("Invalid node assignment, cannot go above the root: " + assignment);
			}
			path.remove(path.size() - 1);
			relative = relative.substring(1);
		}
		if (relative.isEmpty())
		{
			throw new WapiException("Invalid node assignment: " + assignment);
		}
		Collections.addAll(path, relative.replace('/', '.').split("\\."));
		return path;
	}
}
